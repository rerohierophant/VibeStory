package com.vibestory.task;

import java.sql.DriverManager;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.springframework.test.annotation.DirtiesContext;

@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
abstract class PostgresSupport {
    private static final String SCHEMA = "vibestory_test_" + UUID.randomUUID().toString().replace("-", "");
    private static PostgreSQLContainer<?> container;
    private static String url;
    private static String username;
    private static String password;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        String external = System.getenv("TEST_DATABASE_URL");
        if (external == null || external.isBlank()) {
            container = new PostgreSQLContainer<>("postgres:16-alpine");
            container.start(); // No silent skips: without Docker or an explicit PG16 URL this fails.
            url = container.getJdbcUrl();
            username = container.getUsername();
            password = container.getPassword();
        } else {
            url = external;
            username = System.getenv().getOrDefault("TEST_DATABASE_USER", "vibestory");
            password = System.getenv().getOrDefault("TEST_DATABASE_PASSWORD", "");
        }
        try (var connection = DriverManager.getConnection(url, username, password)) {
            int major = connection.getMetaData().getDatabaseMajorVersion();
            if (major != 16) throw new IllegalStateException("集成测试要求 PostgreSQL 16，实际版本 " + major);
        } catch (java.sql.SQLException error) { throw new IllegalStateException(error); }
        properties.add("spring.datasource.url", () -> url + (url.contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA);
        properties.add("spring.datasource.username", () -> username);
        properties.add("spring.datasource.password", () -> password);
        properties.add("spring.flyway.schemas", () -> SCHEMA);
        properties.add("vibestory.seed.email", () -> "");
        properties.add("vibestory.seed.password", () -> "");
        properties.add("vibestory.storage.root", () -> "build/test-storage");
        properties.add("vibestory.storage.cleanup-enabled", () -> "false");
        properties.add("vibestory.text.provider", () -> "mock");
        properties.add("vibestory.image.provider", () -> "mock");
        properties.add("vibestory.video.provider", () -> "mock");
    }

    @AfterAll
    static void cleanDatabase() throws Exception {
        if (url != null) {
            try (var connection = DriverManager.getConnection(url, username, password);
                 var statement = connection.createStatement()) {
                statement.execute("drop schema if exists " + SCHEMA + " cascade");
            }
        }
        if (container != null) container.stop();
    }
}
