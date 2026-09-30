package ru.corelia.providerflowable;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.flowable.engine.ProcessEngineConfiguration;
import org.flowable.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoreliaServiceTaskRetryTest {
    @Test
    void dispatchesAsyncCommandAndKeepsFailedJobForRetry() {
        var attempts = new AtomicInteger();
        var delegate = new CoreliaServiceTaskDelegate(task -> {
            attempts.incrementAndGet();
            assertEquals("document-command", task.taskType());
            assertEquals("approve", task.command());
            throw new IllegalStateException("Временная ошибка");
        });
        var configuration = new StandaloneInMemProcessEngineConfiguration();
        configuration.setJdbcUrl("jdbc:h2:mem:flowable-service-task;DB_CLOSE_DELAY=-1");
        configuration.setJdbcDriver("org.h2.Driver");
        configuration.setJdbcUsername("sa"); configuration.setJdbcPassword("");
        configuration.setDatabaseSchemaUpdate(ProcessEngineConfiguration.DB_SCHEMA_UPDATE_TRUE);
        configuration.setAsyncExecutorActivate(false);
        configuration.setBeans(Map.of("coreliaServiceTask", delegate));
        var engine = configuration.buildProcessEngine();
        try {
            engine.getRepositoryService().createDeployment().addString("service.bpmn20.xml", bpmn()).deploy();
            engine.getRuntimeService().startProcessInstanceByKey("service-task", java.util.Map.of(
                    FlowableWorkflowProvider.DOCUMENT_ID, "document-1",
                    FlowableWorkflowProvider.DOCUMENT_TYPE, "APPLICATION"));
            var job = engine.getManagementService().createJobQuery().singleResult();

            assertThrows(RuntimeException.class, () -> engine.getManagementService().executeJob(job.getId()));

            var retry = engine.getManagementService().createTimerJobQuery().singleResult();
            assertEquals(1, attempts.get());
            assertTrue(retry.getRetries() < job.getRetries());
        } finally {
            engine.close();
        }
    }

    private static String bpmn() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                  xmlns:flowable="http://flowable.org/bpmn"
                  xmlns:corelia="urn:corelia:bpmn" targetNamespace="urn:corelia:test">
                  <process id="service-task" isExecutable="true">
                    <startEvent id="start"><outgoing>start-to-command</outgoing></startEvent>
                    <serviceTask id="command" flowable:delegateExpression="${coreliaServiceTask}" flowable:async="true" flowable:failedJobRetryTimeCycle="R3/PT1M"
                      corelia:taskType="document-command" corelia:command="approve">
                      <incoming>start-to-command</incoming><outgoing>command-to-end</outgoing>
                    </serviceTask>
                    <endEvent id="end"><incoming>command-to-end</incoming></endEvent>
                    <sequenceFlow id="start-to-command" sourceRef="start" targetRef="command"/>
                    <sequenceFlow id="command-to-end" sourceRef="command" targetRef="end"/>
                  </process>
                </definitions>
                """;
    }
}
