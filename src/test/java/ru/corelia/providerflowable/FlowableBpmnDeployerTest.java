package ru.corelia.providerflowable;

import java.nio.file.Files;
import java.nio.file.Path;
import org.flowable.engine.ProcessEngineConfiguration;
import org.flowable.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
