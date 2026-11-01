package com.lonelight.contentplanner.support;

import java.util.UUID;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.mysql.MySQLContainer;

/** One isolated MySQL instance per test JVM; it never touches the application database. */
public abstract class MySqlIntegrationSupport {
    private static class DatabaseHolder {
        private static final MySQLContainer INSTANCE = startDatabase();

        private static MySQLContainer startDatabase() {
            var database =
                    new MySQLContainer("mysql:8.4.7")
                            .withDatabaseName("planner_test")
                            .withUsername("planner_test")
                            .withPassword(UUID.randomUUID().toString())
                            .withUrlParam("allowPublicKeyRetrieval", "true")
                            .withUrlParam("useSSL", "false");
            database.start();
            return database;
        }
    }

    @DynamicPropertySource
    protected static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DatabaseHolder.INSTANCE::getJdbcUrl);
        registry.add("spring.datasource.username", DatabaseHolder.INSTANCE::getUsername);
        registry.add("spring.datasource.password", DatabaseHolder.INSTANCE::getPassword);
    }

    protected static String[] applicationArguments() {
        return new String[] {
            "--spring.datasource.url=" + DatabaseHolder.INSTANCE.getJdbcUrl(),
            "--spring.datasource.username=" + DatabaseHolder.INSTANCE.getUsername(),
            "--spring.datasource.password=" + DatabaseHolder.INSTANCE.getPassword(),
            "--agent.model-mode=mock",
            "--agent.simulation-enabled=true",
            "--spring.main.banner-mode=off",
            "--logging.level.root=ERROR"
        };
    }
}
