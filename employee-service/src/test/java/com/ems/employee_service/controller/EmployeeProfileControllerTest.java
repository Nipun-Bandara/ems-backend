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
@Import(SecurityConfig.class)
class EmployeeProfileControllerTest {
    @Autowired
    MockMvc mvc;

    @MockitoBean
    EmployeeAvatarService avatars;

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
}
