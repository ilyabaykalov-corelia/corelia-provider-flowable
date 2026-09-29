package ru.corelia.providerflowable;

import java.time.Instant;
import org.springframework.dao.DuplicateKeyException;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Хранит идемпотентную привязку команды создания к экземпляру Flowable. */
@Repository
public final class ProcessBindingRepository {
    private final JdbcTemplate jdbc;

    public ProcessBindingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Binding> find(String documentId, String documentType, String action, String key) {
        return jdbc.query("""
                select process_instance_id, definition_key, definition_version
                  from process_binding
                 where document_id = ? and document_type = ? and action = ? and idempotency_key = ? and engine = 'flowable'
                """, (result, row) -> new Binding(result.getString(1), result.getString(2), result.getObject(3, Integer.class)),
                documentId, documentType, action, key).stream().findFirst();
    }

    /** Резервирует ключ запуска; false означает, что его уже обрабатывает другой запрос. */
    public boolean reserve(String documentId, String documentType, String action, String key, String definitionKey) {
        try {
            return jdbc.update("""
                    insert into process_binding (document_id, document_type, action, idempotency_key, definition_key, engine, created_at)
                    values (?, ?, ?, ?, ?, 'flowable', ?)
                    """, documentId, documentType, action, key, definitionKey, Instant.now()) == 1;
        } catch (DuplicateKeyException ignored) {
            return false;
        }
    }

    public void bind(String documentId, String documentType, String action, String key, String processInstanceId, int definitionVersion) {
        if (jdbc.update("""
                update process_binding set process_instance_id = ?, definition_version = ?
                 where document_id = ? and document_type = ? and action = ? and idempotency_key = ? and engine = 'flowable'
                """, processInstanceId, definitionVersion, documentId, documentType, action, key) != 1)
            throw new IllegalStateException("Не найдена зарезервированная привязка Flowable процесса");
    }

    public record Binding(String processInstanceId, String definitionKey, Integer definitionVersion) { }
}
