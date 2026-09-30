package ru.corelia.providerflowable;

import java.nio.file.Path;
import org.flowable.engine.RepositoryService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Последовательно развёртывает BPMN при одновременном запуске нескольких replicas. */
@Component
public class FlowableBpmnDeploymentLock {
    private final JdbcTemplate jdbc;

    public FlowableBpmnDeploymentLock(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public void deployIfMissing(RepositoryService repository, String documentType, String key, Path resource) throws Exception {
        jdbc.queryForObject("select lock_id from flowable_bpmn_deployment_lock where lock_id = 1 for update", Integer.class);
        FlowableBpmnDeployer.deployIfMissing(repository, documentType, key, resource);
    }
}
