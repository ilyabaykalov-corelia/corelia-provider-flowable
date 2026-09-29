package ru.corelia.providerflowable;

import org.flowable.engine.TaskService;
import org.springframework.stereotype.Component;
import ru.corelia.provider.WorkflowEngineResolver;

/** Находит принадлежащие Flowable identifier без передачи их в legacy provider. */
@Component
public final class FlowableEngineResolver implements WorkflowEngineResolver {
    private final ProcessBindingRepository bindings;
    private final TaskService tasks;

    public FlowableEngineResolver(ProcessBindingRepository bindings, TaskService tasks) {
        this.bindings = bindings;
        this.tasks = tasks;
    }

    @Override
    public boolean ownsProcess(String processInstanceId) {
        return bindings.ownsProcess(processInstanceId);
    }

    @Override
    public boolean ownsTask(String taskId) {
        return tasks.createTaskQuery().taskId(taskId).count() > 0;
    }
}
