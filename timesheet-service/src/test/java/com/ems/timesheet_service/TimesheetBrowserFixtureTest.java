package com.ems.timesheet_service;

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
@EnabledIfEnvironmentVariable(named = "TIMESHEET_BROWSER_FIXTURE", matches = "true")
class TimesheetBrowserFixtureTest {
    @Test
    void serve() throws Exception {
        try (var db = new PostgreSQLContainer("postgres:17").withDatabaseName("timesheet_browser_fixture");
                var rabbit = new RabbitMQContainer("rabbitmq:4-management")) {
            db.start();
            rabbit.start();
            try (var ctx = new SpringApplication(TimesheetServiceApplication.class)
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
                LocalDate today = LocalDate.now(ZoneId.of("Asia/Colombo"));
                ctx.getBean(ProjectionStore.class)
                        .backfill(
                                1,
                                new Models.Backfill(
                                        Instant.now().minusSeconds(5),
                                        today.minusDays(14),
                                        List.of(new Models.Employee(6101, true), new Models.Employee(6102, true)),
                                        List.of(
                                                new Models.ProjectSnapshot(
                                                        61, 61, "Browser fixture project", false, null, null),
                                                new Models.ProjectSnapshot(
                                                        62, 62, "Other team project", false, null, null)),
                                        List.of(
                                                new Models.Membership(61, 6101, true),
                                                new Models.Membership(62, 6102, true))));
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
