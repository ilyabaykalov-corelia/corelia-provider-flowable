package ru.corelia.providerflowable;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.ConcurrentHashMap;
import org.flowable.common.engine.api.delegate.event.FlowableEngineEventType;
import org.flowable.common.engine.api.delegate.event.FlowableEvent;
import org.flowable.common.engine.api.delegate.event.FlowableEventListener;
import org.flowable.engine.ManagementService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.delegate.event.FlowableProcessEngineEvent;
import org.springframework.stereotype.Component;

/** Публикует runtime-состояние и события Flowable через стандартные метрики Corelia. */
@Component
public final class FlowableMetrics implements FlowableEventListener {
    private final Counter started;
    private final Counter completed;
    private final Counter failedJobs;
    private final Timer duration;
    private final ConcurrentHashMap<String, Timer.Sample> executions = new ConcurrentHashMap<>();

    public FlowableMetrics(MeterRegistry registry, RuntimeService runtime, TaskService tasks, ManagementService management) {
        started = registry.counter("corelia.workflow.processes.started");
        completed = registry.counter("corelia.workflow.processes.completed");
        failedJobs = registry.counter("corelia.workflow.jobs.failed");
        duration = registry.timer("corelia.workflow.process.duration");
        Gauge.builder("corelia.workflow.processes.active", runtime,
                service -> service.createProcessInstanceQuery().count()).register(registry);
        Gauge.builder("corelia.workflow.tasks.active", tasks,
                service -> service.createTaskQuery().active().count()).register(registry);
        Gauge.builder("corelia.workflow.jobs.dead_letter", management,
                service -> service.createDeadLetterJobQuery().count()).register(registry);
        runtime.addEventListener(this, FlowableEngineEventType.PROCESS_STARTED,
                FlowableEngineEventType.PROCESS_COMPLETED, FlowableEngineEventType.JOB_EXECUTION_FAILURE);
    }

    @Override
    public void onEvent(FlowableEvent event) {
        if (event.getType() == FlowableEngineEventType.JOB_EXECUTION_FAILURE) failedJobs.increment();
        if (!(event instanceof FlowableProcessEngineEvent process)) return;
        String id = process.getExecution().getProcessInstanceId();
        if (event.getType() == FlowableEngineEventType.PROCESS_STARTED) {
            started.increment(); executions.put(id, Timer.start());
        } else if (event.getType() == FlowableEngineEventType.PROCESS_COMPLETED) {
            completed.increment();
            Timer.Sample sample = executions.remove(id);
            if (sample != null) sample.stop(duration);
        }
    }

    @Override public boolean isFailOnException() { return false; }
    @Override public boolean isFireOnTransactionLifecycleEvent() { return false; }
    @Override public String getOnTransaction() { return null; }
}
