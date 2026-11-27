package com.mediflow.agent.service.knowledge;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.util.function.Supplier;

@Service
@Profile("mysql")
public final class KnowledgeIndexMaintenanceLock {
    private static final String INDEX_LOCK_NAME = "SHA2(CONCAT(DATABASE(), ':mediflow-knowledge-index'), 256)";
    private final JdbcTemplate jdbc;

    public KnowledgeIndexMaintenanceLock(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    /** Coordinates collection verification and destructive rebuilds, including the separate CLI process. */
    public <T> T withIndexMaintenanceLock(Supplier<T> operation) {
        return jdbc.execute((ConnectionCallback<T>) connection -> {
            try (var statement = connection.createStatement()) {
                try (var result = statement.executeQuery("SELECT GET_LOCK(" + INDEX_LOCK_NAME + ", 0)")) {
                    if (!result.next() || result.getInt(1) != 1) throw new IndexMaintenanceBusy();
                }
                try {
                    return operation.get();
                } finally {
                    statement.execute("SELECT RELEASE_LOCK(" + INDEX_LOCK_NAME + ")");
                }
            }
        });
    }

    public static final class IndexMaintenanceBusy extends IllegalStateException {
        IndexMaintenanceBusy() {
            super("资料索引正在发布或重建，请稍后再试。");
        }
    }
}
