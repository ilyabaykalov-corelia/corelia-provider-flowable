package ru.corelia.providerflowable;

import java.util.LinkedHashMap;
import java.util.Map;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RuntimeService;
import org.springframework.stereotype.Component;
import ru.corelia.auth.AuthContext;
import ru.corelia.http.ApiException;
import ru.corelia.provider.WorkflowProvider;
import ru.corelia.provider.model.ProcessInstance;
import ru.corelia.provider.model.WorkflowContext;
import ru.corelia.support.Json;

/** Flowable-реализация запуска и чтения процессов Corelia. */
@Component
public final class FlowableWorkflowProvider implements WorkflowProvider {
    static final String DOCUMENT_ID = "coreliaDocumentId";
    static final String DOCUMENT_TYPE = "coreliaDocumentType";
    static final String ATTRIBUTES = "coreliaAttributes";
    static final String CREATED_BY = "coreliaCreatedBy";
    static final String CREATION_KEY = "coreliaCreationKey";
    static final String CREATION_HASH = "coreliaCreationHash";

    private final RuntimeService runtime;
    private final HistoryService history;
    private final FlowableWorkflowBindings bindings;

    public FlowableWorkflowProvider(RuntimeService runtime, HistoryService history, FlowableWorkflowBindings bindings) {
        this.runtime = runtime;
        this.history = history;
        this.bindings = bindings;
    }

    @Override
    public ProcessInstance start(WorkflowContext context, AuthContext auth) {
        var instance = runtime.startProcessInstanceByKey(
                bindings.definitionKey(context.documentType()), context.externalBusinessKey(), variables(context));
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
}
