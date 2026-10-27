package com.ems.employee_service.controller;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.ems.employee_service.security.SecurityConfig;
import com.ems.employee_service.service.EmployeeAvatarService;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(EmployeeProfileController.class)
@Import({SecurityConfig.class, com.ems.employee_service.security.EmployeeProfileAccess.class})
class EmployeeProfileControllerTest {
    @Autowired
    MockMvc mvc;

    @MockitoBean
    EmployeeAvatarService avatars;

    @MockitoBean
    com.ems.employee_service.service.EmployeeProfileService profiles;

    @MockitoBean
    com.ems.employee_service.repository.EmployeeProfileRepository repository;

    @Test
    void anonymousCannotRequestUpload() throws Exception {
        mvc.perform(post("/api/employees/42/avatar/upload-url")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contentType\":\"image/png\",\"size\":42}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(avatars);
    }

    @Test
    void otherEmployeeCannotReadOrChangeAvatar() throws Exception {
        mvc.perform(get("/api/employees/42").header("X-User-Id", "43")).andExpect(status().isForbidden());
        mvc.perform(post("/api/employees/42/avatar/upload-url")
                        .header("X-User-Id", "43")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contentType\":\"image/png\",\"size\":42}"))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/employees/42/avatar")
                        .header("X-User-Id", "43")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"objectKey\":\"other-key\"}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(avatars);
    }

    @Test
    void selfAndAdminMayRequestUpload() throws Exception {
        when(avatars.requestUpload(42L, "image/png", 42L))
                .thenReturn(
                        new EmployeeAvatarService.Upload("https://storage/put", "employees/42/avatars/key", Map.of()));
        for (String[] identity : new String[][] {{"42", "EMPLOYEE"}, {"43", "SYSTEM_ADMIN"}}) {
            mvc.perform(post("/api/employees/42/avatar/upload-url")
                            .header("X-User-Id", identity[0])
                            .header("X-User-Roles", identity[1])
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"contentType\":\"image/png\",\"size\":42}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.objectKey").value("employees/42/avatars/key"));
        }
    }

    @Test
    void rejectsOversizeAndMissingMetadata() throws Exception {
        for (String body : new String[] {"{\"contentType\":\"image/png\",\"size\":5242881}", "{}"}) {
            mvc.perform(post("/api/employees/42/avatar/upload-url")
                            .header("X-User-Id", "42")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(avatars);
    }

    private static final String VALID =
            "{\"firstName\":\"Ada\",\"lastName\":\"Lovelace\",\"phone\":\"+94771234567\",\"dateOfBirth\":\"1990-12-10\"}";

    @Test
    void selfEndpointsUseAuthenticatedIdentity() throws Exception {
        mvc.perform(get("/api/employees/me").header("X-User-Id", "42")).andExpect(status().isOk());
        mvc.perform(put("/api/employees/me")
                        .header("X-User-Id", "42")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID))
                .andExpect(status().isOk());
        verify(avatars).getProfile(42L);
        verify(profiles).update(eq(42L), any());
    }

    @Test
    void onlyAdminAndHrCanUpdateById() throws Exception {
        for (String role : new String[] {"EMPLOYEE", "DEPARTMENT_HEAD"}) {
            mvc.perform(put("/api/employees/42")
                            .header("X-User-Id", "43")
                            .header("X-User-Roles", role)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(VALID))
                    .andExpect(status().isForbidden());
        }
        for (String role : new String[] {"SYSTEM_ADMIN", "HR_MANAGER"}) {
            mvc.perform(put("/api/employees/42")
                            .header("X-User-Id", "43")
                            .header("X-User-Roles", role)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(VALID))
                    .andExpect(status().isOk());
            mvc.perform(get("/api/employees/42").header("X-User-Id", "43").header("X-User-Roles", role))
                    .andExpect(status().isOk());
        }
    }

    @Test
    void departmentHeadMustShareANonNullDepartment() throws Exception {
        var caller = org.mockito.Mockito.mock(com.ems.employee_service.entity.EmployeeProfile.class);
        var target = org.mockito.Mockito.mock(com.ems.employee_service.entity.EmployeeProfile.class);
        when(repository.findById(43L)).thenReturn(java.util.Optional.of(caller));
        when(repository.findById(42L)).thenReturn(java.util.Optional.of(target));
        when(caller.getDepartmentId()).thenReturn(7L);
        when(target.getDepartmentId()).thenReturn(7L);
        mvc.perform(get("/api/employees/42").header("X-User-Id", "43").header("X-User-Roles", "DEPARTMENT_HEAD"))
                .andExpect(status().isOk());
        when(target.getDepartmentId()).thenReturn(8L);
        mvc.perform(get("/api/employees/42").header("X-User-Id", "43").header("X-User-Roles", "DEPARTMENT_HEAD"))
                .andExpect(status().isForbidden());
        when(caller.getDepartmentId()).thenReturn(null);
        when(target.getDepartmentId()).thenReturn(null);
        mvc.perform(get("/api/employees/42").header("X-User-Id", "43").header("X-User-Roles", "DEPARTMENT_HEAD"))
                .andExpect(status().isForbidden());
    }

    @Test
    void rejectsInvalidProfileFields() throws Exception {
        for (String body : new String[] {
            "{}",
            VALID.replace("Ada", " "),
            VALID.replace("+94771234567", "bad-phone"),
            VALID.replace("1990-12-10", "2990-12-10"),
            VALID.substring(0, VALID.length() - 1) + ",\"designationId\":0}",
            VALID.substring(0, VALID.length() - 1) + ",\"reportingManagerUserId\":-1}",
            VALID.substring(0, VALID.length() - 1) + ",\"joinDate\":\"2990-01-01\"}",
            VALID.substring(0, VALID.length() - 1) + ",\"emergencyContactPhone\":\"bad\"}",
            VALID.substring(0, VALID.length() - 1) + ",\"emergencyContactName\":\" \"}",
            VALID.substring(0, VALID.length() - 1) + ",\"address\":\" \"}"
        }) {
            mvc.perform(put("/api/employees/me")
                            .header("X-User-Id", "42")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(profiles);
    }
}
