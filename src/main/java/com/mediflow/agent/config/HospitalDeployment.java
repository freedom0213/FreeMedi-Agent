package com.mediflow.agent.config;

import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
@Profile("mysql")
@ConditionalOnProperty(name = "mediflow.setup.required", havingValue = "true")
public class HospitalDeployment {
    @Bean
    FlywayMigrationStrategy hospitalBootstrap() {
        return flyway -> {
            var source = flyway.getConfiguration().getDataSource();
            var jdbc = new JdbcTemplate(source);
            var tables = jdbc.queryForList("""
                    SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE()
                    """, String.class);
            boolean fresh = tables.isEmpty();
            if (!fresh && (!tables.contains("hospital_settings") || !Boolean.TRUE.equals(jdbc.queryForObject(
                    "SELECT initial_setup_required FROM hospital_settings WHERE id=1", Boolean.class)))) {
                throw new IllegalStateException("首次启用模式需要全新的数据库。已有环境请沿用原启动方式。");
            }
            flyway.migrate();
            if (fresh) {
                // Only this startup owns the schema and its rows, created by the embedded migrations above.
                new TransactionTemplate(new DataSourceTransactionManager(source)).executeWithoutResult(tx -> {
                    jdbc.update("""
                            DELETE s FROM appointment_slot s JOIN doctor_schedule sc ON s.schedule_id=sc.id
                            WHERE sc.doctor_id BETWEEN 3001 AND 3007
                            """);
                    jdbc.update("DELETE FROM doctor_schedule WHERE doctor_id BETWEEN 3001 AND 3007");
                    jdbc.update("DELETE FROM doctor WHERE id BETWEEN 3001 AND 3007");
                    jdbc.update("DELETE FROM department WHERE id BETWEEN 2001 AND 2006");
                    jdbc.update("DELETE FROM admin_grant WHERE admin_id IN (SELECT id FROM sys_user WHERE username='demo-admin')");
                    jdbc.update("DELETE FROM sys_user WHERE username IN ('demo-patient','demo-patient-b','demo-admin')");
                    jdbc.update("UPDATE hospital_settings SET initial_setup_required=TRUE WHERE id=1");
                });
            }
        };
    }
}
