package com.ems.org_service.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.ems.common.outbox.OutboxPoller;
import com.ems.org_service.entity.*;
import com.ems.org_service.repository.*;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = {"ems.messaging.enabled=false", "spring.jpa.hibernate.ddl-auto=validate"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@Transactional
class ProjectIntegrationTest {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    TeamRepository teams;

    @Autowired
    TeamMemberRepository members;

    @Autowired
    DepartmentRepository departments;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ProjectRepository projects;

    @Autowired
    JsonMapper json;
    // Keep the real transactional publisher, while preventing background broker delivery.
    @MockitoBean
    OutboxPoller poller;

    Long teamId;
    Long otherTeamId;

    @BeforeEach
    void setUp() {
        var now = LocalDateTime.now();
        var department = departments.save(DepartmentEntity.builder()
                .departmentName("Engineering")
                .createdAt(now)
                .build());
        teamId = teams.save(TeamEntity.builder()
                        .name("Platform")
                        .departmentId(department.getDepartmentId())
                        .createdAt(now)
                        .build())
                .getId();
        otherTeamId = teams.save(TeamEntity.builder()
                        .name("Operations")
                        .departmentId(department.getDepartmentId())
                        .createdAt(now)
                        .build())
                .getId();
        members.saveAndFlush(new TeamMemberEntity(teamId, 42L, now));
    }

    MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, long userId, String role) {
        return request.header("X-User-Id", userId).header("X-User-Roles", role).contentType(MediaType.APPLICATION_JSON);
    }

    String body(Long team, String status) {
        return """
            {"name":"Launch", "description":"Customer launch", "teamId":%d, "status":"%s",
            "startDate":"2026-10-01", "endDate":"2026-11-01"}
            """.formatted(team, status);
    }

    long create(Long team, String status) throws Exception {
        var result = mvc.perform(as(post("/api/projects"), 1L, "SYSTEM_ADMIN").content(body(team, status)))
                .andExpect(status().isCreated())
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString())
                .get("id")
                .asLong();
    }

    @Test
    void memberSeesProjectNonMemberDoesNotAndMembershipChangesTakeEffect() throws Exception {
        long id = create(teamId, "PLANNED");
        mvc.perform(as(get("/api/projects/my"), 42L, "EMPLOYEE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projects.length()").value(1))
                .andExpect(jsonPath("$.projects[0].id").value(id));
        mvc.perform(as(get("/api/projects/my?userId=42&teamId=" + teamId), 99L, "EMPLOYEE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projects").isEmpty());
        // Even administrators receive only their membership projects on /my.
        mvc.perform(as(get("/api/projects/my"), 1L, "SYSTEM_ADMIN"))
                .andExpect(jsonPath("$.projects").isEmpty());
        members.deleteById(new TeamMemberId(teamId, 42L));
        members.flush();
        mvc.perform(as(get("/api/projects/my"), 42L, "EMPLOYEE"))
                .andExpect(jsonPath("$.projects").isEmpty());
        members.saveAndFlush(new TeamMemberEntity(teamId, 99L, LocalDateTime.now()));
        mvc.perform(as(get("/api/projects/my"), 99L, "EMPLOYEE"))
                .andExpect(jsonPath("$.projects[0].id").value(id));
    }

    @Test
    void filtersAndPaginationApplyToAllAndMyProjects() throws Exception {
        create(teamId, "ACTIVE");
        create(teamId, "PLANNED");
        create(otherTeamId, "ACTIVE");
        for (String path : new String[] {"/api/projects", "/api/projects/my"}) {
            mvc.perform(as(get(path + "?teamId=" + teamId + "&status=ACTIVE"), 42L, "EMPLOYEE"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.projects.length()").value(1))
                    .andExpect(jsonPath("$.projects[0].status").value("ACTIVE"));
            mvc.perform(as(get(path + "?teamId=" + teamId + "&page=0&limit=1"), 42L, "EMPLOYEE"))
                    .andExpect(jsonPath("$.hasNext").value(true))
                    .andExpect(jsonPath("$.hasPrevious").value(false));
            mvc.perform(as(get(path + "?teamId=" + teamId + "&page=1&limit=1"), 42L, "EMPLOYEE"))
                    .andExpect(jsonPath("$.hasNext").value(false))
                    .andExpect(jsonPath("$.hasPrevious").value(true));
        }
        mvc.perform(as(get("/api/projects/my?teamId=" + otherTeamId), 42L, "EMPLOYEE"))
                .andExpect(jsonPath("$.projects").isEmpty());
    }

    @Test
    void crudPublishesEventsAndReassignmentChangesVisibility() throws Exception {
        long id = create(teamId, "PLANNED");
        mvc.perform(as(get("/api/projects/" + id), 42L, "EMPLOYEE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Launch"));
        mvc.perform(as(put("/api/projects/" + id), 1L, "DEPARTMENT_HEAD").content(body(otherTeamId, "ACTIVE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.teamId").value(otherTeamId));
        mvc.perform(as(get("/api/projects/my"), 42L, "EMPLOYEE"))
                .andExpect(jsonPath("$.projects").isEmpty());
        mvc.perform(as(put("/api/projects/" + id), 1L, "SYSTEM_ADMIN").content(body(otherTeamId, "ACTIVE")))
                .andExpect(status().isOk());
        projects.flush();
        assertThat(jdbc.queryForList(
                        "select type from outbox_event where aggregate_type = 'project' order by created_at",
                        String.class))
                .containsExactly("project.created", "project.updated", "project.status-changed", "project.updated");
        assertThat(jdbc.queryForObject(
                        "select payload ->> 'previousStatus' from outbox_event where type = 'project.status-changed'",
                        String.class))
                .isEqualTo("PLANNED");
        mvc.perform(as(delete("/api/projects/" + id), 1L, "SYSTEM_ADMIN")).andExpect(status().isNoContent());
        mvc.perform(as(get("/api/projects/" + id), 42L, "EMPLOYEE")).andExpect(status().isNotFound());
    }

    @ParameterizedTest
    @ValueSource(strings = {"EMPLOYEE", "USER", "HR_MANAGER", "FINANCE_MANAGER"})
    void otherRolesCannotWrite(String role) throws Exception {
        mvc.perform(as(post("/api/projects"), 42L, role).content(body(teamId, "ACTIVE")))
                .andExpect(status().isForbidden());
        mvc.perform(as(put("/api/projects/1"), 42L, role).content(body(teamId, "ACTIVE")))
                .andExpect(status().isForbidden());
        mvc.perform(as(delete("/api/projects/1"), 42L, role)).andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("select count(*) from projects", Long.class))
                .isZero();
        assertThat(jdbc.queryForObject("select count(*) from outbox_event", Long.class))
                .isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"SYSTEM_ADMIN", "DEPARTMENT_HEAD"})
    void managersCanCreateUpdateDelete(String role) throws Exception {
        var result = mvc.perform(as(post("/api/projects"), 1L, role).content(body(teamId, "PLANNED")))
                .andExpect(status().isCreated())
                .andReturn();
        long id = json.readTree(result.getResponse().getContentAsString())
                .get("id")
                .asLong();
        mvc.perform(as(put("/api/projects/" + id), 1L, role).content(body(teamId, "COMPLETED")))
                .andExpect(status().isOk());
        mvc.perform(as(delete("/api/projects/" + id), 1L, role)).andExpect(status().isNoContent());
    }

    @Test
    void rejectsInvalidRequestsAndMissingResources() throws Exception {
        for (String path : new String[] {"/api/projects", "/api/projects/my", "/api/projects/1"}) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
        }
        for (String query : new String[] {"page=-1", "limit=0", "limit=101", "teamId=-1", "status=UNKNOWN"}) {
            mvc.perform(as(get("/api/projects?" + query), 42L, "EMPLOYEE")).andExpect(status().isBadRequest());
            mvc.perform(as(get("/api/projects/my?" + query), 42L, "EMPLOYEE")).andExpect(status().isBadRequest());
        }
        for (String invalid : new String[] {
            body(teamId, "ACTIVE").replace("Launch", " "),
            body(teamId, "UNKNOWN"),
            body(teamId, "ACTIVE").replace("2026-11-01", "2026-09-01")
        }) {
            mvc.perform(as(post("/api/projects"), 1L, "SYSTEM_ADMIN").content(invalid))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(as(post("/api/projects"), 1L, "SYSTEM_ADMIN").content(body(Long.MAX_VALUE, "ACTIVE")))
                .andExpect(status().isNotFound());
        mvc.perform(as(get("/api/projects/999999"), 42L, "EMPLOYEE")).andExpect(status().isNotFound());
        mvc.perform(as(put("/api/projects/999999"), 1L, "SYSTEM_ADMIN").content(body(teamId, "ACTIVE")))
                .andExpect(status().isNotFound());
        mvc.perform(as(delete("/api/projects/999999"), 1L, "SYSTEM_ADMIN")).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("select count(*) from projects", Long.class))
                .isZero();
        assertThat(jdbc.queryForObject("select count(*) from outbox_event", Long.class))
                .isZero();
    }
}
