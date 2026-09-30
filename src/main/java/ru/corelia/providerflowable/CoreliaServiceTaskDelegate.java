package ru.corelia.providerflowable;

import org.flowable.bpmn.model.FlowElement;
import org.flowable.bpmn.model.ServiceTask;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
import org.springframework.stereotype.Component;
import ru.corelia.configuration.ConfigurationException;
import ru.corelia.provider.WorkflowServiceTaskExecutor;
import ru.corelia.provider.model.WorkflowServiceTask;

/** Передаёт разрешённую BPMN service task владельцу workflow-команды Corelia. */
@Component("coreliaServiceTask")
public final class CoreliaServiceTaskDelegate implements JavaDelegate {
    private final WorkflowServiceTaskExecutor executor;

    public CoreliaServiceTaskDelegate(WorkflowServiceTaskExecutor executor) {
        this.executor = executor;
    }

    @Override
    public void execute(DelegateExecution execution) {
        FlowElement element = execution.getCurrentFlowElement();
        if (!(element instanceof ServiceTask task))
            throw new ConfigurationException("Не найдена BPMN service task для выполнения");
        executor.execute(new WorkflowServiceTask(
                CoreliaBpmnProfile.serviceTaskType(task), task.getId(), execution.getId(),
                execution.getProcessInstanceId(), value(execution, FlowableWorkflowProvider.DOCUMENT_ID),
                value(execution, FlowableWorkflowProvider.DOCUMENT_TYPE), CoreliaBpmnProfile.serviceTaskCommand(task)));
    }

    private static String value(DelegateExecution execution, String name) {
        Object value = execution.getVariable(name);
        return value instanceof String text ? text : "";
    }
}
