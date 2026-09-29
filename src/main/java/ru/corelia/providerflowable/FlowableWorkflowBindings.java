package ru.corelia.providerflowable;

import org.springframework.stereotype.Component;
import ru.corelia.configuration.ConfigurationException;
import ru.corelia.configuration.ConfigurationLoader;
import tools.jackson.databind.JsonNode;

/** Предоставляет Flowable-специфичные настройки процесса вне доменной модели Corelia. */
@Component
public final class FlowableWorkflowBindings {
    private final ConfigurationLoader.LoadedConfiguration configuration;

    public FlowableWorkflowBindings(ConfigurationLoader.LoadedConfiguration configuration) {
        this.configuration = configuration;
    }

    /** Возвращает явно заданный ключ определения Flowable для вида документа. */
    public String definitionKey(String documentType) {
        return definitionKey(documentType, configuration.providerBindings().get(documentType));
    }

    static String definitionKey(String documentType, JsonNode binding) {
        JsonNode flowable = binding == null ? null : binding.path("workflow").path("flowable");
        if (flowable == null || !flowable.isObject()
                || !flowable.path("definitionKey").isTextual()
                || flowable.path("definitionKey").asString().isBlank())
            throw new ConfigurationException("Не задан Flowable process binding: " + documentType);
        return flowable.path("definitionKey").asString();
    }
}
