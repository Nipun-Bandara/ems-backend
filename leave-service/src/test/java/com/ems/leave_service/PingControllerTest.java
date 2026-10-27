package com.ems.leave_service;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ems.leave_service.controller.PingController;
import com.ems.leave_service.security.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(PingController.class)
@Import(SecurityConfig.class)
class PingControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void rejectsRequestsWithoutGatewayIdentity() throws Exception {
        mockMvc.perform(get("/api/leaves/ping")).andExpect(status().isUnauthorized());
    }

    @Test
    void returnsServiceStatusForAnAuthenticatedGatewayRequest() throws Exception {
        mockMvc.perform(get("/api/leaves/ping").header("X-User-Id", "42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.service").value("leave-service"))
                .andExpect(jsonPath("$.status").value("ok"));
    }

    @Test
    void leavesHealthPublic() throws Exception {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isNotFound());
    }
}
