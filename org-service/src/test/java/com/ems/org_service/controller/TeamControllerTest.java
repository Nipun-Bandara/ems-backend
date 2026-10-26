package com.ems.org_service.controller;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.ems.org_service.dto.response.TeamResponse;
import com.ems.org_service.exception.TeamNotFoundException;
import com.ems.org_service.security.SecurityConfig;
import com.ems.org_service.service.TeamService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@WebMvcTest(TeamController.class)
@Import(SecurityConfig.class)
class TeamControllerTest {
    @Autowired
    MockMvc mvc;

    @MockitoBean
    TeamService teams;

    static final String BODY = "{\"name\":\"Platform\",\"departmentId\":4,\"leadUserId\":999}";

    MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, String role) {
        return request.header("X-User-Id", "42").header("X-User-Roles", role).contentType(MediaType.APPLICATION_JSON);
    }

    @Test
    void anonymousCannotRead() throws Exception {
        mvc.perform(get("/api/teams")).andExpect(status().isUnauthorized());
    }

    @Test
    void employeeReadsTeamsAndMembership() throws Exception {
        when(teams.list(4L)).thenReturn(List.of());
        when(teams.get(9L))
                .thenReturn(
                        new TeamResponse(9L, "Platform", 4L, null, null, List.of(new TeamResponse.Member(42L, null))));
        mvc.perform(as(get("/api/teams?departmentId=4"), "EMPLOYEE")).andExpect(status().isOk());
        verify(teams).list(4L);
        mvc.perform(as(get("/api/teams/9"), "EMPLOYEE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.members[0].userId").value(42));
    }

    @ParameterizedTest
    @ValueSource(strings = {"EMPLOYEE", "USER", "HR_MANAGER", "FINANCE_MANAGER"})
    void otherRolesCannotWrite(String role) throws Exception {
        mvc.perform(as(post("/api/teams"), role).content(BODY)).andExpect(status().isForbidden());
        mvc.perform(as(put("/api/teams/9"), role).content(BODY)).andExpect(status().isForbidden());
        mvc.perform(as(delete("/api/teams/9"), role)).andExpect(status().isForbidden());
        mvc.perform(as(post("/api/teams/9/members"), role).content("{\"userId\":42}"))
                .andExpect(status().isForbidden());
        mvc.perform(as(delete("/api/teams/9/members/42"), role)).andExpect(status().isForbidden());
        verifyNoInteractions(teams);
    }

    @ParameterizedTest
    @ValueSource(strings = {"SYSTEM_ADMIN", "DEPARTMENT_HEAD"})
    void managersCanWriteEveryEndpoint(String role) throws Exception {
        mvc.perform(as(post("/api/teams"), role).content(BODY)).andExpect(status().isCreated());
        mvc.perform(as(put("/api/teams/9"), role).content(BODY)).andExpect(status().isOk());
        mvc.perform(as(delete("/api/teams/9"), role)).andExpect(status().isNoContent());
        mvc.perform(as(post("/api/teams/9/members"), role).content("{\"userId\":42}"))
                .andExpect(status().isOk());
        mvc.perform(as(delete("/api/teams/9/members/42"), role)).andExpect(status().isNoContent());
    }

    @Test
    void invalidInputIsRejectedAndMissingTeamIs404() throws Exception {
        mvc.perform(as(post("/api/teams"), "SYSTEM_ADMIN").content("{\"name\":\" \",\"departmentId\":-1}"))
                .andExpect(status().isBadRequest());
        mvc.perform(as(post("/api/teams/9/members"), "SYSTEM_ADMIN").content("{\"userId\":0}"))
                .andExpect(status().isBadRequest());
        mvc.perform(as(get("/api/teams?departmentId=-1"), "EMPLOYEE")).andExpect(status().isBadRequest());
        when(teams.get(99L)).thenThrow(new TeamNotFoundException(99L));
        mvc.perform(as(get("/api/teams/99"), "EMPLOYEE")).andExpect(status().isNotFound());
    }
}
