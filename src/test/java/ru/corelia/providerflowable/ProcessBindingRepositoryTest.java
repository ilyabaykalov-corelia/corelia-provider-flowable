package ru.corelia.providerflowable;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessBindingRepositoryTest {
    private ProcessBindingRepository bindings;

    @BeforeEach
    void setUp() {
        var source = new JdbcDataSource(); source.setURL("jdbc:h2:mem:bindings;MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        var jdbc = new JdbcTemplate(source);
        jdbc.execute("drop table if exists process_binding");
        jdbc.execute("""
                create table process_binding (
                    document_id varchar(255) not null, document_type varchar(128) not null, action varchar(128) not null,
                    idempotency_key varchar(255) not null, process_instance_id varchar(255), definition_key varchar(255) not null,
                    definition_version int, engine varchar(64) not null, created_at timestamp with time zone not null,
                    primary key (document_id, document_type, action, idempotency_key))
                """);
        bindings = new ProcessBindingRepository(jdbc);
    }

    @Test
    void reservesAndBindsOnlyOneProcessForTheKey() {
        assertTrue(bindings.reserve("doc-1", "CONTRACT", "create", "request-1", "approval"));
        assertFalse(bindings.reserve("doc-1", "CONTRACT", "create", "request-1", "approval"));

        bindings.bind("doc-1", "CONTRACT", "create", "request-1", "flowable-1", 2);
        var binding = bindings.find("doc-1", "CONTRACT", "create", "request-1").orElseThrow();
        assertEquals("flowable-1", binding.processInstanceId());
        assertEquals("approval", binding.definitionKey());
        assertEquals(2, binding.definitionVersion());
    }
}
