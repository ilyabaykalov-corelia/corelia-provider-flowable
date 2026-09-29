package ru.corelia.providerflowable;

import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.ExtensionAttribute;
import org.flowable.bpmn.model.ExtensionElement;
import org.flowable.bpmn.model.Process;
import org.flowable.bpmn.model.UserTask;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CoreliaBpmnActionsTest {
    @Test
    void readsActionAndItsParametersFromUserTaskExtension() {
        var action = element("action"); action.addAttribute(attribute("id", "approve")); action.addAttribute(attribute("label", "Approve"));
        action.addAttribute(attribute("status", "APPROVED"));
        var parameter = element("parameter"); parameter.addAttribute(attribute("name", "decision")); parameter.addAttribute(attribute("value", "approved")); action.addChildElement(parameter);
        var task = new UserTask(); task.setId("review"); task.addExtensionElement(action);

        var result = CoreliaBpmnActions.actions(model(task), "review").getFirst();
        assertEquals("approve", result.code());
        assertEquals("APPROVED", result.status());
        assertEquals("approved", result.parameters().get("decision").asString());
        assertEquals("approve", result.parameters().get("action").asString());
    }

    private static ExtensionElement element(String name) { var value = new ExtensionElement(); value.setName(name); value.setNamespace("urn:corelia:bpmn"); return value; }
    private static ExtensionAttribute attribute(String name, String value) { var result = new ExtensionAttribute(name, value); result.setNamespace("urn:corelia:bpmn"); return result; }
    private static BpmnModel model(UserTask task) { var process = new Process(); process.setId("approval"); process.addFlowElement(task); var model = new BpmnModel(); model.addProcess(process); return model; }
}
