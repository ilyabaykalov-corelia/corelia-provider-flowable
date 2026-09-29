package ru.corelia.providerflowable;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import org.flowable.bpmn.converter.BpmnXMLConverter;
import org.flowable.engine.RepositoryService;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import ru.corelia.configuration.ConfigurationException;
import ru.corelia.configuration.DocumentTypeCatalog;

/** Развёртывает проверенные BPMN из configuration package как неизменяемые Flowable версии. */
@Component
public final class FlowableBpmnDeployer implements ApplicationRunner {
    private final RepositoryService repository;
    private final DocumentTypeCatalog types;
    private final FlowableWorkflowBindings bindings;

    public FlowableBpmnDeployer(RepositoryService repository, DocumentTypeCatalog types, FlowableWorkflowBindings bindings) {
        this.repository = repository;
        this.types = types;
        this.bindings = bindings;
    }

    @Override
    public void run(org.springframework.boot.ApplicationArguments arguments) {
        for (String type : types.types()) deploy(type);
    }

    private void deploy(String documentType) {
        try {
            String key = bindings.definitionKey(documentType);
            Path resource = bindings.bpmnResource(documentType);
            deploy(repository, documentType, key, resource);
        } catch (ConfigurationException error) {
            throw error;
        } catch (Exception error) {
            throw new ConfigurationException("Не удалось развернуть Flowable BPMN: " + documentType, error);
        }
    }

    static void deploy(RepositoryService repository, String documentType, String key, Path resource) throws Exception {
        byte[] source = Files.readAllBytes(resource);
        var model = new BpmnXMLConverter().convertToBpmnModel(() -> new ByteArrayInputStream(source), false, false);
        CoreliaBpmnProfile.validate(model);
        if (model.getProcessById(key) == null)
            throw new ConfigurationException("BPMN process key не соответствует Flowable binding: " + documentType);
        String checksum = checksum(source), name = "corelia:" + key + ":" + checksum;
        if (repository.createDeploymentQuery().deploymentName(name).count() == 0)
            repository.createDeployment().name(name).key(key).addBytes(resource.getFileName().toString(), source)
                    .enableDuplicateFiltering().deploy();
    }

    private static String checksum(byte[] source) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(source);
        return java.util.HexFormat.of().formatHex(hash);
    }
}
