package ru.corelia.providerflowable;

import java.util.Set;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.EndEvent;
import org.flowable.bpmn.model.Event;
import org.flowable.bpmn.model.ExclusiveGateway;
import org.flowable.bpmn.model.FlowElement;
import org.flowable.bpmn.model.IntermediateCatchEvent;
import org.flowable.bpmn.model.ParallelGateway;
import org.flowable.bpmn.model.SequenceFlow;
import org.flowable.bpmn.model.ServiceTask;
import org.flowable.bpmn.model.StartEvent;
import org.flowable.bpmn.model.TimerEventDefinition;
import org.flowable.bpmn.model.UserTask;
import ru.corelia.configuration.ConfigurationException;

/** Ограничивает BPMN customer package безопасным профилем Corelia. */
public final class CoreliaBpmnProfile {
    private static final Set<String> SERVICE_TASK_TYPES = Set.of("document-command");

    private CoreliaBpmnProfile() { }

    /** Проверяет элементы и запрещает произвольные реализации service task. */
    public static void validate(BpmnModel model) {
        for (var process : model.getProcesses()) for (FlowElement element : process.getFlowElements()) {
            if (element instanceof ServiceTask task) validateServiceTask(task);
            else if (element instanceof UserTask task) validateUserTask(task);
            else if (element instanceof IntermediateCatchEvent timer) validateTimer(timer);
            else if (!(element instanceof StartEvent || element instanceof EndEvent
                    || element instanceof ExclusiveGateway || element instanceof ParallelGateway || element instanceof SequenceFlow))
                throw new ConfigurationException("Элемент BPMN не поддерживается профилем Corelia: " + element.getClass().getSimpleName());
        }
    }

    private static void validateTimer(IntermediateCatchEvent timer) {
        if (timer.getEventDefinitions().isEmpty() || timer.getEventDefinitions().stream()
                .anyMatch(definition -> !(definition instanceof TimerEventDefinition)))
            throw new ConfigurationException("Разрешены только timer intermediate catch events: " + timer.getId());
    }

    private static void validateServiceTask(ServiceTask task) {
        if (!"${coreliaServiceTask}".equals(task.getImplementation())
                || !"delegateExpression".equals(task.getImplementationType()) || !blank(task.getType()))
            throw new ConfigurationException("Service task должен использовать контролируемый delegate Corelia: " + task.getId());
        if (!task.isAsynchronous())
            throw new ConfigurationException("Service task должен выполняться через Flowable async executor: " + task.getId());
        if (blank(task.getFailedJobRetryTimeCycleValue()))
            throw new ConfigurationException("Service task должен задавать retry policy: " + task.getId());
        String type = serviceTaskType(task);
        if (!SERVICE_TASK_TYPES.contains(type))
            throw new ConfigurationException("Не задан разрешённый Corelia service task type: " + task.getId());
        if (type.equals("document-command") && serviceTaskCommand(task).isBlank())
            throw new ConfigurationException("Не задана document command service task: " + task.getId());
        if (!serviceTaskCommand(task).matches("[A-Za-z][A-Za-z0-9_-]{0,127}"))
            throw new ConfigurationException("Некорректная document command service task: " + task.getId());
    }

    private static void validateUserTask(UserTask task) {
        String groups = attribute(task, "candidateGroups");
        if (!groups.isBlank()) for (String group : groups.split(",")) {
            if (!group.trim().matches("[A-Za-z][A-Za-z0-9_.-]{0,127}"))
                throw new ConfigurationException("Некорректная candidate group user task: " + task.getId());
        }
    }

    static String serviceTaskType(ServiceTask task) { return attribute(task, "taskType"); }
    static String serviceTaskCommand(ServiceTask task) { return attribute(task, "command"); }

    private static String attribute(UserTask task, String name) {
        return task.getAttributes().values().stream().flatMap(java.util.Collection::stream)
                .filter(attribute -> name.equals(attribute.getName())).map(attribute -> attribute.getValue())
                .filter(value -> value != null && !value.isBlank()).findFirst().orElse("");
    }

    private static String attribute(ServiceTask task, String name) {
        return task.getAttributes().values().stream().flatMap(java.util.Collection::stream)
                .filter(attribute -> name.equals(attribute.getName())).map(attribute -> attribute.getValue())
                .filter(value -> value != null && !value.isBlank()).findFirst().orElse("");
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
}
