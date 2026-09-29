package ru.corelia.providerflowable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import ru.corelia.configuration.ConfigurationException;
import ru.corelia.configuration.ConfigurationLoader;
import ru.corelia.provider.model.WorkflowAction;
import ru.corelia.support.Json;
import tools.jackson.databind.JsonNode;

/** Предоставляет Flowable-специфичные настройки процесса вне доменной модели Corelia. */
@Component
public final class FlowableWorkflowBindings {
    private final Map<String, JsonNode> bindings;

    public FlowableWorkflowBindings(ConfigurationLoader.LoadedConfiguration configuration) {
        this(configuration.providerBindings());
    }

    FlowableWorkflowBindings(Map<String, JsonNode> bindings) {
        this.bindings = Map.copyOf(bindings);
    }

    /** Возвращает явно заданный ключ определения Flowable для вида документа. */
    public String definitionKey(String documentType) {
        return definitionKey(documentType, bindings.get(documentType));
    }

    static String definitionKey(String documentType, JsonNode binding) {
        JsonNode flowable = binding == null ? null : binding.path("workflow").path("flowable");
        if (flowable == null || !flowable.isObject()
                || !flowable.path("definitionKey").isTextual()
                || flowable.path("definitionKey").asString().isBlank())
            throw new ConfigurationException("Не задан Flowable process binding: " + documentType);
        return flowable.path("definitionKey").asString();
    }

    /** Возвращает разрешённые configuration package действия для Flowable user task. */
    public List<WorkflowAction> actions(String documentType, String taskDefinitionKey) {
        JsonNode binding = bindings.get(documentType);
        if (binding == null) throw new ConfigurationException("Не задан Flowable binding: " + documentType);
        JsonNode actions = binding.path("workflow").path("flowable")
                .path("tasks").path(taskDefinitionKey).path("actions");
        if (actions.isMissingNode() || actions.isNull()) return List.of();
        if (!actions.isArray()) throw new ConfigurationException("Некорректные Flowable task actions: " + documentType + "." + taskDefinitionKey);
        var result = new ArrayList<WorkflowAction>();
        for (JsonNode action : actions) {
            if (!action.isObject() || !action.path("code").isTextual() || action.path("code").asString().isBlank()
                    || !action.path("label").isTextual() || action.path("label").asString().isBlank())
                throw new ConfigurationException("Некорректное Flowable task action: " + documentType + "." + taskDefinitionKey);
            JsonNode parameters = action.path("parameters");
            if (!parameters.isMissingNode() && !parameters.isObject())
                throw new ConfigurationException("Некорректные параметры Flowable task action: " + documentType + "." + taskDefinitionKey);
            var values = new LinkedHashMap<String, JsonNode>();
            if (parameters.isObject()) parameters.properties().forEach(entry -> values.put(entry.getKey(), entry.getValue()));
            result.add(new WorkflowAction(action.path("code").asString(), action.path("label").asString(),
                    Json.text(action, "status"), Json.text(action, "tone").isEmpty() ? "success" : Json.text(action, "tone"), values));
        }
        return List.copyOf(result);
    }
}
