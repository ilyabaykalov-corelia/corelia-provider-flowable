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
    private final FlowableBpmnDeploymentLock deploymentLock;

    public FlowableBpmnDeployer(RepositoryService repository, DocumentTypeCatalog types, FlowableWorkflowBindings bindings,
                                FlowableBpmnDeploymentLock deploymentLock) {
        this.repository = repository;
        this.types = types;
        this.bindings = bindings;
        this.deploymentLock = deploymentLock;
    }

    @Override
    public void run(org.springframework.boot.ApplicationArguments arguments) {
        for (String type : types.types()) deploy(type);
    }

    private void deploy(String documentType) {
        try {
            String key = bindings.definitionKey(documentType);
            Path resource = bindings.bpmnResource(documentType);
            deploymentLock.deployIfMissing(repository, documentType, key, resource);
        } catch (ConfigurationException error) {
            throw error;
        } catch (Exception error) {
            throw new ConfigurationException("Не удалось развернуть Flowable BPMN: " + documentType, error);
        }
    }

    static void deployIfMissing(RepositoryService repository, String documentType, String key, Path resource) throws Exception {
        byte[] source = Files.readAllBytes(resource);
        deployIfMissing(repository, key, resource.getFileName().toString(), source);
    }

    static void deployIfMissing(RepositoryService repository, String key, String resourceName, byte[] source) throws Exception {
        var model = new BpmnXMLConverter().convertToBpmnModel(() -> new ByteArrayInputStream(source), false, false);
        CoreliaBpmnProfile.validate(model);
        if (model.getProcessById(key) == null)
            throw new ConfigurationException("BPMN process key не соответствует ключу Corelia: " + key);
        String checksum = checksum(source), name = "corelia:" + key + ":" + checksum;
        var latest = repository.createProcessDefinitionQuery().processDefinitionKey(key).latestVersion().singleResult();
        if (latest != null) {
            var latestDeployment = repository.createDeploymentQuery().deploymentId(latest.getDeploymentId()).singleResult();
            if (latestDeployment != null && name.equals(latestDeployment.getName())) return;
        }
        repository.createDeployment().name(name).key(key).addBytes(resourceName, source).deploy();
    }

    private static String checksum(byte[] source) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(source);
        return java.util.HexFormat.of().formatHex(hash);
    }
}
