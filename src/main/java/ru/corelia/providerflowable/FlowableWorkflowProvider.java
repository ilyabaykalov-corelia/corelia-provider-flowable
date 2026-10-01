package ru.corelia.providerflowable;

import java.util.LinkedHashMap;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.xml.parsers.DocumentBuilderFactory;
import org.flowable.bpmn.converter.BpmnXMLConverter;
import org.flowable.bpmn.model.SequenceFlow;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ru.corelia.auth.AuthContext;
import ru.corelia.http.ApiException;
import ru.corelia.provider.WorkflowProvider;
import ru.corelia.provider.model.ProcessInstance;
import ru.corelia.provider.model.WorkflowDefinition;
import ru.corelia.provider.model.WorkflowContext;
import ru.corelia.provider.model.WorkflowValidation;
import ru.corelia.provider.model.WorkflowValidationError;
import ru.corelia.support.Json;

/** Flowable-реализация запуска и чтения процессов Corelia. */
@Component("flowableWorkflowProvider")
public class FlowableWorkflowProvider implements WorkflowProvider {
    static final String DOCUMENT_ID = "coreliaDocumentId";
    static final String DOCUMENT_TYPE = "coreliaDocumentType";
    static final String ATTRIBUTES = "coreliaAttributes";
    static final String CREATED_BY = "coreliaCreatedBy";
    static final String CREATION_KEY = "coreliaCreationKey";
    static final String CREATION_HASH = "coreliaCreationHash";

    private final RuntimeService runtime;
    private final HistoryService history;
    private final RepositoryService repository;
    private final FlowableWorkflowBindings bindings;
    private final ProcessBindingRepository processBindings;
    private final FlowableBpmnDeploymentLock deploymentLock;

    @org.springframework.beans.factory.annotation.Autowired
    public FlowableWorkflowProvider(RuntimeService runtime, HistoryService history, RepositoryService repository, FlowableWorkflowBindings bindings,
                                    ProcessBindingRepository processBindings) {
        this(runtime, history, repository, bindings, processBindings, null);
    }

    public FlowableWorkflowProvider(RuntimeService runtime, HistoryService history, RepositoryService repository, FlowableWorkflowBindings bindings,
                                    ProcessBindingRepository processBindings, FlowableBpmnDeploymentLock deploymentLock) {
        this.runtime = runtime;
        this.history = history;
        this.repository = repository;
        this.bindings = bindings;
        this.processBindings = processBindings;
        this.deploymentLock = deploymentLock;
    }

    @Override
    @Transactional
    public ProcessInstance start(WorkflowContext context, AuthContext auth) {
        String definitionKey = bindings.definitionKey(context.documentType());
        String action = "create", key = idempotencyKey(context);
        var existing = processBindings.find(context.documentId(), context.documentType(), action, key);
        if (existing.isPresent() && existing.get().processInstanceId() != null)
            return process(existing.get().processInstanceId(), auth);
        if (existing.isEmpty() && !processBindings.reserve(context.documentId(), context.documentType(), action, key, definitionKey))
            existing = processBindings.find(context.documentId(), context.documentType(), action, key);
        if (existing.isPresent()) {
            if (existing.get().processInstanceId() != null) return process(existing.get().processInstanceId(), auth);
            throw new ApiException(409, "Запуск процесса с этим ключом ещё выполняется");
        }
        var instance = runtime.startProcessInstanceByKey(definitionKey, context.externalBusinessKey(), variables(context));
        processBindings.bind(context.documentId(), context.documentType(), action, key, instance.getId(), instance.getProcessDefinitionVersion());
        return new ProcessInstance(instance.getId(), context.documentId(), "ACTIVE", "flowable");
    }

    @Override
    public ProcessInstance process(String processInstanceId, AuthContext auth) {
        var active = runtime.createProcessInstanceQuery().processInstanceId(processInstanceId).singleResult();
        if (active != null)
            return new ProcessInstance(active.getId(), documentId(active.getProcessVariables()), "ACTIVE", "flowable");
        var completed = history.createHistoricProcessInstanceQuery()
                .processInstanceId(processInstanceId).includeProcessVariables().singleResult();
        if (completed == null) throw new ApiException(404, "Экземпляр процесса не найден");
        return new ProcessInstance(completed.getId(), documentId(completed.getProcessVariables()),
                completed.getEndTime() == null ? "ACTIVE" : "COMPLETED", "flowable");
    }

    @Override
    public java.util.List<WorkflowDefinition> definitions(AuthContext auth) {
        return repository.createProcessDefinitionQuery().latestVersion().list().stream()
                .map(definition -> {
                    var deployment = repository.createDeploymentQuery().deploymentId(definition.getDeploymentId()).singleResult();
                    if (deployment == null || deployment.getName() == null || !deployment.getName().startsWith("corelia:")) return null;
                    return new WorkflowDefinition(
                            definition.getName() == null || definition.getName().isBlank() ? definition.getKey() : definition.getName(),
                            definition.getKey(), definition.getVersion(), false, "PUBLISHED",
                            deployment == null ? null : deployment.getDeploymentTime().toInstant(), null,
                            runtime.createProcessInstanceQuery().processDefinitionKey(definition.getKey()).count());
                }).filter(java.util.Objects::nonNull).sorted(java.util.Comparator.comparing(WorkflowDefinition::key)).toList();
    }

    @Override
    public WorkflowValidation validateDefinition(String key, String bpmnXml, AuthContext auth) {
        try {
            validateNamespaces(bpmnXml);
            var model = new BpmnXMLConverter().convertToBpmnModel(
                    () -> new ByteArrayInputStream(bpmnXml.getBytes(StandardCharsets.UTF_8)), false, false);
            if (model.getProcessById(key) == null)
                return invalid("PROCESS_KEY", "BPMN process id должен совпадать с ключом процесса: " + key);
            CoreliaBpmnProfile.validate(model);
            for (var process : model.getProcesses()) for (var element : process.getFlowElements()) {
                if (element instanceof SequenceFlow flow && (flow.getSourceRef() == null || flow.getTargetRef() == null))
                    return invalid("SEQUENCE_FLOW", "Sequence flow должен иметь исходный и целевой элементы: " + flow.getId());
            }
            return new WorkflowValidation(List.of());
        } catch (RuntimeException error) {
            return invalid("BPMN", message(error));
        }
    }

    @Override
    public WorkflowDefinition publishDefinition(String key, String name, String bpmnXml, AuthContext auth) {
        var validation = validateDefinition(key, bpmnXml, auth);
        if (!validation.valid()) throw new ApiException(400, validation.errors().getFirst().message());
        if (deploymentLock == null) throw new IllegalStateException("Не настроена блокировка публикации BPMN");
        try {
            deploymentLock.deployDraft(repository, key, bpmnXml);
        } catch (Exception error) {
            throw new ApiException(500, "Не удалось опубликовать BPMN процесс");
        }
        var definition = repository.createProcessDefinitionQuery().processDefinitionKey(key).latestVersion().singleResult();
        var deployment = repository.createDeploymentQuery().deploymentId(definition.getDeploymentId()).singleResult();
        return new WorkflowDefinition(name, key, definition.getVersion(), true, "PUBLISHED",
                deployment.getDeploymentTime().toInstant(), auth.login(),
                runtime.createProcessInstanceQuery().processDefinitionKey(key).count());
    }

    private static WorkflowValidation invalid(String code, String message) {
        return new WorkflowValidation(List.of(new WorkflowValidationError(code, message)));
    }

    /** Разрешает только BPMN, DI, Flowable и Corelia namespaces в XML черновика. */
    private static void validateNamespaces(String bpmnXml) {
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            var document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(bpmnXml.getBytes(StandardCharsets.UTF_8)));
            var allowed = Set.of("http://www.omg.org/spec/BPMN/20100524/MODEL", "http://www.omg.org/spec/BPMN/20100524/DI",
                    "http://www.omg.org/spec/DD/20100524/DI", "http://www.omg.org/spec/DD/20100524/DC", "http://flowable.org/bpmn",
                    "urn:corelia:bpmn", "http://www.w3.org/2001/XMLSchema-instance", "http://www.w3.org/XML/1998/namespace");
            var nodes = document.getElementsByTagName("*");
            for (int index = 0; index < nodes.getLength(); index++) {
                var node = nodes.item(index);
                if (!allowed.contains(node.getNamespaceURI())) throw new IllegalArgumentException("Неизвестный BPMN namespace: " + node.getNamespaceURI());
                var attributes = node.getAttributes();
                for (int attribute = 0; attribute < attributes.getLength(); attribute++) {
                    var value = attributes.item(attribute);
                    if (!value.getNodeName().startsWith("xmlns") && value.getNamespaceURI() != null && !allowed.contains(value.getNamespaceURI()))
                        throw new IllegalArgumentException("Неизвестный BPMN extension: " + value.getNodeName());
                }
            }
        } catch (Exception error) {
            if (error instanceof IllegalArgumentException invalid) throw invalid;
            throw new IllegalArgumentException("Некорректный BPMN XML", error);
        }
    }

    private static String message(RuntimeException error) {
        return error.getMessage() == null || error.getMessage().isBlank()
                ? "Некорректный BPMN XML" : error.getMessage();
    }

    private static Map<String, Object> variables(WorkflowContext context) {
        var variables = new LinkedHashMap<String, Object>();
        variables.put(DOCUMENT_ID, context.documentId());
        variables.put(DOCUMENT_TYPE, context.documentType());
        variables.put(ATTRIBUTES, Json.write(context.attributes()));
        variables.put(CREATED_BY, context.createdBy());
        variables.put(CREATION_KEY, context.creationKey());
        variables.put(CREATION_HASH, context.creationHash());
        return variables;
    }

    private static String documentId(Map<String, Object> variables) {
        Object value = variables.get(DOCUMENT_ID);
        return value instanceof String id ? id : "";
    }

    private static String idempotencyKey(WorkflowContext context) {
        if (context.creationKey() != null && !context.creationKey().isBlank()) return context.creationKey();
        return context.documentType() + ":" + context.documentId() + ":create";
    }
}
