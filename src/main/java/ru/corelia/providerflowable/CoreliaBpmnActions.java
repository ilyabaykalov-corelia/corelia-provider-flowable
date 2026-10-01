package ru.corelia.providerflowable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.ExtensionElement;
import org.flowable.bpmn.model.FlowElement;
import org.flowable.bpmn.model.UserTask;
import ru.corelia.configuration.ConfigurationException;
import ru.corelia.provider.model.WorkflowAction;
import ru.corelia.support.Json;
import tools.jackson.databind.JsonNode;

/** Читает доступные действия user task из неизменяемой версии BPMN Flowable. */
public final class CoreliaBpmnActions {
    private CoreliaBpmnActions() { }

    public static List<WorkflowAction> actions(BpmnModel model, String taskDefinitionKey) {
        FlowElement element = model.getFlowElement(taskDefinitionKey);
        if (!(element instanceof UserTask task))
            throw new ConfigurationException("Не найдена Flowable user task: " + taskDefinitionKey);
        var result = new ArrayList<WorkflowAction>();
        for (ExtensionElement action : task.getExtensionElements().getOrDefault("action", List.of())) {
            String code = attribute(action, "id"), label = attribute(action, "label");
            if (code.isBlank() || label.isBlank())
                throw new ConfigurationException("Некорректный corelia:action у task: " + taskDefinitionKey);
            String status = attribute(action, "status"), tone = attribute(action, "tone");
            var parameters = new LinkedHashMap<String, JsonNode>();
            parameters.put("action", Json.MAPPER.getNodeFactory().textNode(code));
            for (ExtensionElement parameter : action.getChildElements().getOrDefault("parameter", List.of())) {
                String name = attribute(parameter, "name"), value = attribute(parameter, "value");
                if (name.isBlank()) throw new ConfigurationException("Некорректный параметр corelia:action: " + code);
                parameters.put(name, Json.MAPPER.getNodeFactory().textNode(value));
            }
            result.add(new WorkflowAction(code, label, status, tone.isBlank() ? "success" : tone, parameters));
        }
        if (result.isEmpty()) actionsFromAttributes(task, result);
        return List.copyOf(result);
    }

    /** Читает компактные действия, которые создаёт административный редактор Corelia. */
    private static void actionsFromAttributes(UserTask task, List<WorkflowAction> result) {
        String[] codes = attribute(task, "actions").split(",");
        String[] labels = attribute(task, "labels").split(",");
        for (int index = 0; index < codes.length; index++) {
            String code = codes[index].trim();
            if (code.isBlank()) continue;
            String label = index < labels.length && !labels[index].trim().isBlank() ? labels[index].trim() : code;
            var parameters = new LinkedHashMap<String, JsonNode>();
            parameters.put("action", Json.MAPPER.getNodeFactory().textNode(code));
            result.add(new WorkflowAction(code, label, "", "success", parameters));
        }
    }

    private static String attribute(ExtensionElement element, String name) {
        return element.getAttributes().values().stream().flatMap(java.util.Collection::stream)
                .filter(attribute -> name.equals(attribute.getName())).map(attribute -> attribute.getValue())
                .filter(value -> value != null).findFirst().orElse("");
    }

    private static String attribute(UserTask task, String name) {
        return task.getAttributes().values().stream().flatMap(java.util.Collection::stream)
                .filter(attribute -> name.equals(attribute.getName())).map(attribute -> attribute.getValue())
                .filter(value -> value != null).findFirst().orElse("");
    }
}
