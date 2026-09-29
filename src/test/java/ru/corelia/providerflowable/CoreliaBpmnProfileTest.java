package ru.corelia.providerflowable;

import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.ExtensionAttribute;
import org.flowable.bpmn.model.Process;
import org.flowable.bpmn.model.ServiceTask;
import org.flowable.bpmn.model.StartEvent;
import org.flowable.bpmn.model.SubProcess;
import org.junit.jupiter.api.Test;
import ru.corelia.configuration.ConfigurationException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CoreliaBpmnProfileTest {
    @Test
    void acceptsControlledDocumentCommandServiceTask() {
        var task = safeServiceTask();
        var attribute = new ExtensionAttribute("taskType", "document-command");
        attribute.setNamespace("urn:corelia:bpmn"); task.addAttribute(attribute);

        assertDoesNotThrow(() -> CoreliaBpmnProfile.validate(model(task)));
    }

    @Test
    void rejectsArbitraryJavaServiceTask() {
        var task = safeServiceTask(); task.setImplementation("com.customer.UnsafeDelegate");

        assertThrows(ConfigurationException.class, () -> CoreliaBpmnProfile.validate(model(task)));
    }

    @Test
    void rejectsUnsupportedBpmnElements() {
        assertThrows(ConfigurationException.class, () -> CoreliaBpmnProfile.validate(model(new SubProcess())));
    }

    @Test
    void rejectsServiceTaskWithoutAsyncRetryPolicy() {
        var task = safeServiceTask(); task.setAsynchronous(false);

        assertThrows(ConfigurationException.class, () -> CoreliaBpmnProfile.validate(model(task)));
    }

    private static ServiceTask safeServiceTask() {
        var task = new ServiceTask(); task.setId("commit"); task.setAsynchronous(true);
        task.setFailedJobRetryTimeCycleValue("R3/PT1M");
        return task;
    }

    private static BpmnModel model(org.flowable.bpmn.model.FlowElement element) {
        var process = new Process(); process.setId("approval"); process.addFlowElement(new StartEvent()); process.addFlowElement(element);
        var model = new BpmnModel(); model.addProcess(process); return model;
    }
}
