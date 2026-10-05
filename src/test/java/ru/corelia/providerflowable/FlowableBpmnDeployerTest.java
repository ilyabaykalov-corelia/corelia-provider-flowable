package ru.corelia.providerflowable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.flowable.engine.ProcessEngineConfiguration;
import org.flowable.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import ru.corelia.http.ApiException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FlowableBpmnDeployerTest {
    @Test
    void createsAnImmutableVersionOnlyWhenBpmnChanges() throws Exception {
        var engine = new StandaloneInMemProcessEngineConfiguration()
                .setJdbcUrl("jdbc:h2:mem:flowable-deployer;DB_CLOSE_DELAY=-1")
                .setJdbcDriver("org.h2.Driver")
                .setJdbcUsername("sa").setJdbcPassword("")
                .setDatabaseSchemaUpdate(ProcessEngineConfiguration.DB_SCHEMA_UPDATE_TRUE)
                .buildProcessEngine();
        Path bpmn = Files.createTempFile("approval", ".bpmn20.xml");
        try {
            Files.writeString(bpmn, bpmn(""));
            FlowableBpmnDeployer.deployIfMissing(engine.getRepositoryService(), "application", "approval", bpmn);
            FlowableBpmnDeployer.deployIfMissing(engine.getRepositoryService(), "application", "approval", bpmn);

            assertEquals(1, engine.getRepositoryService().createProcessDefinitionQuery()
                    .processDefinitionKey("approval").count());

            Files.writeString(bpmn, bpmn("<userTask id=\"review\" name=\"Review\"/>"));
            FlowableBpmnDeployer.deployIfMissing(engine.getRepositoryService(), "application", "approval", bpmn);

            assertEquals(2, engine.getRepositoryService().createProcessDefinitionQuery()
                    .processDefinitionKey("approval").count());
        } finally {
            engine.close();
            Files.deleteIfExists(bpmn);
        }
    }

    @Test
    void restoresCustomerBpmnAsLatestAfterRuntimeVersionWithoutDuplicatingAnUnchangedBootstrap() throws Exception {
        var engine = new StandaloneInMemProcessEngineConfiguration()
                .setJdbcUrl("jdbc:h2:mem:flowable-config-restore;DB_CLOSE_DELAY=-1")
                .setJdbcDriver("org.h2.Driver").setJdbcUsername("sa").setJdbcPassword("")
                .setDatabaseSchemaUpdate(ProcessEngineConfiguration.DB_SCHEMA_UPDATE_TRUE).buildProcessEngine();
        try {
            var repository = engine.getRepositoryService();
            String customer = approvalBpmn("Customer review");
            FlowableBpmnDeployer.deployIfMissing(repository, "approval", "approval.bpmn20.xml", customer.getBytes());
            var first = engine.getRuntimeService().startProcessInstanceByKey("approval");

            FlowableBpmnDeployer.deployIfMissing(repository, "approval", "approval.bpmn20.xml", approvalBpmn("Runtime review").getBytes());
            var second = engine.getRuntimeService().startProcessInstanceByKey("approval");
            FlowableBpmnDeployer.deployIfMissing(repository, "approval", "approval.bpmn20.xml", customer.getBytes());
            var third = engine.getRuntimeService().startProcessInstanceByKey("approval");
            FlowableBpmnDeployer.deployIfMissing(repository, "approval", "approval.bpmn20.xml", customer.getBytes());

            assertEquals(3, repository.createProcessDefinitionQuery().processDefinitionKey("approval").count());
            assertEquals(1, engine.getRuntimeService().createProcessInstanceQuery().processInstanceId(first.getId()).singleResult().getProcessDefinitionVersion());
            assertEquals(2, engine.getRuntimeService().createProcessInstanceQuery().processInstanceId(second.getId()).singleResult().getProcessDefinitionVersion());
            assertEquals(3, engine.getRuntimeService().createProcessInstanceQuery().processInstanceId(third.getId()).singleResult().getProcessDefinitionVersion());
        } finally { engine.close(); }
    }

    @Test
    void rejectsPublishingFromAnOutdatedVersion() throws Exception {
        String url = "jdbc:h2:mem:flowable-stale-publish;DB_CLOSE_DELAY=-1";
        var engine = new StandaloneInMemProcessEngineConfiguration()
                .setJdbcUrl(url).setJdbcDriver("org.h2.Driver").setJdbcUsername("sa").setJdbcPassword("")
                .setDatabaseSchemaUpdate(ProcessEngineConfiguration.DB_SCHEMA_UPDATE_TRUE).buildProcessEngine();
        try {
            var jdbc = new JdbcTemplate(new DriverManagerDataSource(url, "sa", ""));
            jdbc.execute("create table flowable_bpmn_deployment_lock (lock_id int primary key)");
            jdbc.update("insert into flowable_bpmn_deployment_lock (lock_id) values (1)");
            var lock = new FlowableBpmnDeploymentLock(jdbc);
            var repository = engine.getRepositoryService();
            FlowableBpmnDeployer.deployIfMissing(repository, "approval", "approval.bpmn20.xml", approvalBpmn("Customer review").getBytes());

            lock.deployDraft(repository, "approval", approvalBpmn("Editor review"), 1);

            assertThrows(ApiException.class, () -> lock.deployDraft(repository, "approval", approvalBpmn("Stale review"), 1));
            assertEquals(2, repository.createProcessDefinitionQuery().processDefinitionKey("approval").count());
        } finally { engine.close(); }
    }

    @Test
    void deploysEditorBpmnAndCreatesCompletableUserTask() throws Exception {
        var engine = new StandaloneInMemProcessEngineConfiguration()
                .setJdbcUrl("jdbc:h2:mem:flowable-editor;DB_CLOSE_DELAY=-1")
                .setJdbcDriver("org.h2.Driver").setJdbcUsername("sa").setJdbcPassword("")
                .setDatabaseSchemaUpdate(ProcessEngineConfiguration.DB_SCHEMA_UPDATE_TRUE).buildProcessEngine();
        try {
            String xml = """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:corelia="urn:corelia:bpmn" targetNamespace="urn:corelia:test">
                      <process id="editor_approval" isExecutable="true"><startEvent id="start"/><userTask id="review" name="Review" corelia:actions="complete" corelia:labels="Завершить"/><endEvent id="end"/>
                        <sequenceFlow id="s1" sourceRef="start" targetRef="review"/><sequenceFlow id="s2" sourceRef="review" targetRef="end"/>
                      </process>
                    </definitions>
                    """;
            FlowableBpmnDeployer.deployIfMissing(engine.getRepositoryService(), "editor_approval", "editor_approval.bpmn20.xml", xml.getBytes());
            var instance = engine.getRuntimeService().startProcessInstanceByKey("editor_approval");
            var task = engine.getTaskService().createTaskQuery().processInstanceId(instance.getId()).singleResult();
            var actions = CoreliaBpmnActions.actions(engine.getRepositoryService().getBpmnModel(task.getProcessDefinitionId()), task.getTaskDefinitionKey());
            assertEquals("complete", actions.getFirst().code());
            engine.getTaskService().complete(task.getId());
            assertFalse(engine.getRuntimeService().createProcessInstanceQuery().processInstanceId(instance.getId()).count() > 0);
        } finally { engine.close(); }
    }

    @Test
    void returnsActivityRuntimeByStableBpmnId() {
        var engine = new StandaloneInMemProcessEngineConfiguration()
                .setJdbcUrl("jdbc:h2:mem:flowable-runtime;DB_CLOSE_DELAY=-1")
                .setJdbcDriver("org.h2.Driver").setJdbcUsername("sa").setJdbcPassword("")
                .setDatabaseSchemaUpdate(ProcessEngineConfiguration.DB_SCHEMA_UPDATE_TRUE).buildProcessEngine();
        try {
            engine.getRepositoryService().createDeployment().addString("runtime.bpmn20.xml", """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" targetNamespace="urn:corelia:test">
                      <process id="runtime_approval" isExecutable="true"><startEvent id="start"/><userTask id="review"/><endEvent id="end"/>
                        <sequenceFlow id="s1" sourceRef="start" targetRef="review"/><sequenceFlow id="s2" sourceRef="review" targetRef="end"/>
                      </process>
                    </definitions>
                    """).deploy();
            engine.getRuntimeService().startProcessInstanceByKey("runtime_approval", Map.of(
                    FlowableWorkflowProvider.DOCUMENT_ID, "document-1",
                    FlowableWorkflowProvider.DOCUMENT_TYPE, "PDS_CONTRACT"));
            var provider = new FlowableWorkflowProvider(engine.getRuntimeService(), engine.getHistoryService(),
                    engine.getRepositoryService(), null, null);

            var runtime = provider.runtime("runtime_approval", null);

            assertEquals(1, runtime.instances().size());
            assertEquals("document-1", runtime.instances().getFirst().documentId());
            assertTrue(runtime.instances().getFirst().activityIds().contains("review"));
            assertTrue(runtime.activities().stream().anyMatch(value -> value.activityId().equals("review") && value.activeInstances() == 1));
        } finally { engine.close(); }
    }

    private static String bpmn(String content) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" targetNamespace="urn:corelia:test">
                  <process id="approval" isExecutable="true">
                    <startEvent id="start"/>
                    %s
                    <endEvent id="end"/>
                  </process>
                </definitions>
                """.formatted(content);
    }

    private static String approvalBpmn(String taskName) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" targetNamespace="urn:corelia:test">
                  <process id="approval" isExecutable="true"><startEvent id="start"/><userTask id="review" name="%s"/><endEvent id="end"/>
                    <sequenceFlow id="s1" sourceRef="start" targetRef="review"/><sequenceFlow id="s2" sourceRef="review" targetRef="end"/>
                  </process>
                </definitions>
                """.formatted(taskName);
    }
}
