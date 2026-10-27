package com.ems.leave_service;

import com.ems.leave_service.balance.LeaveBalanceService;
import com.ems.leave_service.balance.LeaveModels;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;

/** Opt-in, disposable backend for browser verification. Never connects to a configured development database. */
@EnabledIfEnvironmentVariable(named = "LEAVE_BROWSER_FIXTURE", matches = "true")
class LeaveBrowserFixtureTest {
    @Test
    void serveDisposableFixture() throws Exception {
        try (var postgres = new PostgreSQLContainer("postgres:17").withDatabaseName("leave_browser_fixture");
                var rabbit = new RabbitMQContainer("rabbitmq:4-management")) {
            postgres.start();
            rabbit.start();
            var app = new SpringApplication(LeaveServiceApplication.class);
            app.setDefaultProperties(Map.of("spring.profiles.active", "test"));
            try (var context = app.run(
                    "--spring.profiles.active=test",
                    "--server.port=0",
                    "--spring.datasource.url=" + postgres.getJdbcUrl(),
                    "--spring.datasource.username=" + postgres.getUsername(),
                    "--spring.datasource.password=" + postgres.getPassword(),
                    "--spring.rabbitmq.host=" + rabbit.getHost(),
                    "--spring.rabbitmq.port=" + rabbit.getAmqpPort(),
                    "--spring.rabbitmq.username=" + rabbit.getAdminUsername(),
                    "--spring.rabbitmq.password=" + rabbit.getAdminPassword())) {
                var broker = context.getBean(org.springframework.amqp.core.AmqpAdmin.class);
                broker.declareQueue(new org.springframework.amqp.core.Queue("leave.browser-audit", true));
                broker.declareBinding(new org.springframework.amqp.core.Binding(
                        "leave.browser-audit",
                        org.springframework.amqp.core.Binding.DestinationType.QUEUE,
                        "ems.events",
                        "leave.#",
                        null));
                var jdbc = context.getBean(JdbcTemplate.class);
                jdbc.update(
                        "INSERT INTO employee_projection(user_id,active) VALUES (6101,true),(6102,true),(6103,true)");
                var service = context.getBean(LeaveBalanceService.class);
                var type =
                        service.saveType(null, new LeaveModels.TypeInput("FIXTURE", "Fixture balance breakdown", true));
                var entitlement = service.allocate(
                        new LeaveModels.Allocation(6103, type.id(), 2026, BigDecimal.TEN, "Browser fixture allocation"),
                        6101);
                var taken = UUID.randomUUID();
                service.reserve(entitlement.id(), taken, new BigDecimal("2.00"), 6103, "Fixture approved request");
                service.settle(entitlement.id(), taken, true, 6101, "Fixture approved");
                service.reserve(entitlement.id(), UUID.randomUUID(), BigDecimal.ONE, 6103, "Fixture pending request");
                var directory = Path.of("target/browser-fixture");
                Files.createDirectories(directory);
                Files.deleteIfExists(directory.resolve("stop"));
                Files.writeString(
                        directory.resolve("port"),
                        Integer.toString(((WebServerApplicationContext) context)
                                .getWebServer()
                                .getPort()));
                long deadline = System.nanoTime() + Duration.ofMinutes(60).toNanos();
                while (!Files.exists(directory.resolve("stop")) && System.nanoTime() < deadline) Thread.sleep(1000);
            }
        }
    }
}
