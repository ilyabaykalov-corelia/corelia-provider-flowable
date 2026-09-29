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
}
