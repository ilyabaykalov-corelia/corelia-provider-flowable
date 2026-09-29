package ru.corelia.providerflowable;

import org.junit.jupiter.api.Test;
import ru.corelia.configuration.ConfigurationException;
import ru.corelia.support.Json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FlowableWorkflowBindingsTest {
    @Test
    void readsDefinitionKeyFromProviderSpecificWorkflowBinding() {
        var binding = Json.object("workflow", Json.object("flowable", Json.object("definitionKey", "contractApproval")));

        assertEquals("contractApproval", FlowableWorkflowBindings.definitionKey("CONTRACT", binding));
    }

    @Test
    void rejectsMissingFlowableBinding() {
        assertThrows(ConfigurationException.class,
                () -> FlowableWorkflowBindings.definitionKey("CONTRACT", Json.object("workflow", Json.object())));
    }

    @Test
    void readsActionsForConcreteTaskDefinition() {
        var bindings = new FlowableWorkflowBindings(java.util.Map.of("CONTRACT", Json.object("workflow", Json.object(
                "flowable", Json.object("tasks", Json.object("review", Json.object("actions", java.util.List.of(
                        Json.object("code", "approve", "label", "Approve", "status", "APPROVED",
                                "parameters", Json.object("decision", "approve"))))))))));

        var action = bindings.actions("CONTRACT", "review").getFirst();
        assertEquals("approve", action.code());
        assertEquals("APPROVED", action.status());
        assertEquals("approve", action.parameters().get("decision").asString());
    }
}
