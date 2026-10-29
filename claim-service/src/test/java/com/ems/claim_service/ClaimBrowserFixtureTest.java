package com.ems.claim_service;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;

/** Opt-in, disposable real backend for browser checks. Never uses a development database. */
@EnabledIfEnvironmentVariable(named = "CLAIM_BROWSER_FIXTURE", matches = "true")
class ClaimBrowserFixtureTest {
    @Test
    void serve() throws Exception {
        try (var db = new PostgreSQLContainer("postgres:17").withDatabaseName("claim_browser_fixture");
                var rabbit = new RabbitMQContainer("rabbitmq:4-management")) {
            db.start();
            rabbit.start();
            try (var ctx = new SpringApplication(ClaimServiceApplication.class)
                    .run(
                            "--spring.profiles.active=test",
                            "--server.port=0",
                            "--spring.datasource.url=" + db.getJdbcUrl(),
                            "--spring.datasource.username=" + db.getUsername(),
                            "--spring.datasource.password=" + db.getPassword(),
                            "--spring.rabbitmq.host=" + rabbit.getHost(),
                            "--spring.rabbitmq.port=" + rabbit.getAmqpPort(),
                            "--spring.rabbitmq.username=" + rabbit.getAdminUsername(),
                            "--spring.rabbitmq.password=" + rabbit.getAdminPassword())) {
                var scope = ctx.getBean(EmployeeScopeStore.class);
                Instant at = Instant.now().minusSeconds(1);
                scope.reconcile(6201, new Models.EmployeeScope(true, 10L, null, at));
                scope.reconcile(6202, new Models.EmployeeScope(true, 20L, null, at));
                scope.reconcile(6101, new Models.EmployeeScope(true, 10L, 6201L, at));
                scope.reconcile(6102, new Models.EmployeeScope(true, 20L, 6202L, at));
                scope.reconcile(6301, new Models.EmployeeScope(true, 10L, null, at));
                scope.reviewer(6301, new Models.Reviewer(Models.Role.HR_MANAGER, at));
                Path directory = Path.of("target/browser-fixture");
                Files.createDirectories(directory);
                Files.deleteIfExists(directory.resolve("stop"));
                Files.writeString(
                        directory.resolve("port"),
                        Integer.toString(((WebServerApplicationContext) ctx)
                                .getWebServer()
                                .getPort()));
                long deadline = System.nanoTime() + Duration.ofMinutes(40).toNanos();
                while (!Files.exists(directory.resolve("stop")) && System.nanoTime() < deadline) Thread.sleep(500);
            }
        }
    }
}
