package com.ems.recruitment_service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"ems.messaging.enabled=false", "spring.config.import="})
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Testcontainers
class RecruitmentIntegrationTest {
    @Container
    static final PostgreSQLContainer DB = new PostgreSQLContainer("postgres:17").withDatabaseName("recruitment_test");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", DB::getJdbcUrl);
        properties.add("spring.datasource.username", DB::getUsername);
        properties.add("spring.datasource.password", DB::getPassword);
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @org.springframework.boot.test.web.server.LocalServerPort
    int port;

    @Test
    void emptyDatabaseMigratesAndCommonOutboxIsInstalled() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE success=true", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_event", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_event", Integer.class))
                .isZero();
    }

    @Test
    void authenticationAndAdministrationPolicy() throws Exception {
        mvc.perform(get("/api/recruitment/ping")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/recruitment/admin/ping")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/recruitment/ping")
                        .header("X-User-Id", "42")
                        .header("X-User-Roles", "EMPLOYEE")
                        .header("X-Correlation-Id", "recruitment-permissions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value("42"))
                .andExpect(header().string("X-Correlation-Id", "recruitment-permissions"));
        for (String role : new String[] {"EMPLOYEE", "DEPARTMENT_HEAD", "", "HR_MANAGER_LOOKALIKE"}) {
            mvc.perform(get("/api/recruitment/admin/ping")
                            .header("X-User-Id", "42")
                            .header("X-User-Roles", role))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.status").value(403));
        }
        for (String role : new String[] {"HR_MANAGER", "SYSTEM_ADMIN", "ROLE_HR_MANAGER", "EMPLOYEE,HR_MANAGER"}) {
            mvc.perform(get("/api/recruitment/admin/ping")
                            .header("X-User-Id", "42")
                            .header("X-User-Roles", role))
                    .andExpect(status().isOk());
        }
    }

    @Test
    void realGatewayRoutesAuthenticatedPingInEveryProfile() throws Exception {
        RecruitmentGatewayProbe.verify(port);
    }
}
