package com.mediflow.agent.api;

import java.sql.Statement;

import javax.sql.DataSource;

import com.mediflow.agent.agent.Principal;
import com.mediflow.agent.agent.TaskId;
import com.mediflow.agent.agent.TaskStateStore;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;

@Service
@Profile("mysql")
public class MySqlTaskApplicationService {
    private final JdbcTemplate jdbc;
    private final TaskStateStore stateStore;

    public MySqlTaskApplicationService(DataSource dataSource, TaskStateStore stateStore) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.stateStore = stateStore;
    }

    public TaskId createTask(Principal principal, String title) {
        if (principal == null) {
            throw new IllegalArgumentException("principal is required");
        }
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("""
                    INSERT INTO agent_session(patient_id, title, status)
                    VALUES (?, ?, 'ACTIVE')
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, principal.userId());
            statement.setString(2, title == null || title.isBlank() ? "预约助手演示会话" : title.trim());
            return statement;
        }, keyHolder);
        Number sessionId = keyHolder.getKey();
        if (sessionId == null) {
            throw new IllegalStateException("could not create agent session");
        }
        try {
            return stateStore.create(sessionId.longValue(), principal.userId());
        } catch (RuntimeException exception) {
            jdbc.update("DELETE FROM agent_session WHERE id = ?", sessionId.longValue());
            throw exception;
        }
    }
}
