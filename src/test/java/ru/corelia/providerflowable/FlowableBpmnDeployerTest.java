package ru.corelia.providerflowable;

import java.nio.file.Files;
import java.nio.file.Path;
import org.flowable.engine.ProcessEngineConfiguration;
import org.flowable.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

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
}
