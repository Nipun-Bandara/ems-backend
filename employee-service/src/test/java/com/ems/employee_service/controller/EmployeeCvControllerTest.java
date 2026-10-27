package com.ems.employee_service.controller;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.ems.employee_service.security.SecurityConfig;
import com.ems.employee_service.service.EmployeeCvSectionsService;
import com.ems.employee_service.service.EmployeeCvService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest({EmployeeCvController.class, EmployeeCvSectionsController.class})
@Import(SecurityConfig.class)
class EmployeeCvControllerTest {
    @Autowired
    MockMvc mvc;

    @MockitoBean
    EmployeeCvService cvs;

    @MockitoBean
    EmployeeCvSectionsService sections;

    private static final String SKILL = "{\"name\":\"Java\",\"proficiency\":\"ADVANCED\",\"yearsOfExperience\":3}";

    @Test
    void deniesAnonymousAndOtherEmployeesForEveryRoute() throws Exception {
        for (String path : new String[] {"skills", "education", "experience", "cv"}) {
            String url = "/api/employees/42/" + path;
            mvc.perform(get(url)).andExpect(status().isUnauthorized());
            mvc.perform(get(url).header("X-User-Id", "43")).andExpect(status().isForbidden());
            // Valid payloads ensure authorization is checked before service access.
            String body =
                    switch (path) {
                        case "skills" -> SKILL;
                        case "education" ->
                            "{\"institution\":\"University\",\"qualification\":\"BSc\",\"startYear\":2020}";
                        case "experience" ->
                            "{\"company\":\"Acme\",\"title\":\"Engineer\",\"startDate\":\"2020-01-01\",\"isCurrent\":true}";
                        default -> "{\"objectKey\":\"issued\"}";
                    };
            mvc.perform(put(url + (path.equals("cv") ? "" : "/1"))
                            .header("X-User-Id", "43")
                            .contentType("application/json")
                            .content(body))
                    .andExpect(status().isForbidden());
            if (!path.equals("cv")) {
                mvc.perform(post(url)
                                .header("X-User-Id", "43")
                                .contentType("application/json")
                                .content(body))
                        .andExpect(status().isForbidden());
                mvc.perform(delete(url + "/1").header("X-User-Id", "43")).andExpect(status().isForbidden());
            }
        }
        mvc.perform(post("/api/employees/42/cv/upload-url")
                        .header("X-User-Id", "43")
                        .contentType("application/json")
                        .content("{\"contentType\":\"application/pdf\",\"size\":10}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(cvs, sections);
    }

    @Test
    void allowsSelfAdminAndHrButDeniesDepartmentHead() throws Exception {
        for (String[] caller : new String[][] {{"42", "EMPLOYEE"}, {"43", "SYSTEM_ADMIN"}, {"43", "HR_MANAGER"}}) {
            for (String path : new String[] {"skills", "education", "experience", "cv"}) {
                mvc.perform(get("/api/employees/42/" + path)
                                .header("X-User-Id", caller[0])
                                .header("X-User-Roles", caller[1]))
                        .andExpect(status().isOk());
            }
            mvc.perform(post("/api/employees/42/skills")
                            .header("X-User-Id", caller[0])
                            .header("X-User-Roles", caller[1])
                            .contentType("application/json")
                            .content(SKILL))
                    .andExpect(status().isCreated());
        }
        mvc.perform(get("/api/employees/42/skills").header("X-User-Id", "43").header("X-User-Roles", "DEPARTMENT_HEAD"))
                .andExpect(status().isForbidden());
    }

    @Test
    void rejectsInvalidFieldsAndInconsistentRanges() throws Exception {
        for (String[] request : new String[][] {
            {"skills", SKILL.replace("ADVANCED", "UNKNOWN")},
            {"skills", SKILL.replace("Java", " ")},
            {"skills", SKILL.replace(":3}", ":-1}")},
            {"education", "{\"institution\":\"A\",\"qualification\":\"B\",\"startYear\":2022,\"endYear\":2020}"},
            {
                "experience",
                "{\"company\":\"A\",\"title\":\"B\",\"startDate\":\"2022-01-01\",\"endDate\":\"2021-01-01\",\"isCurrent\":false}"
            },
            {
                "experience",
                "{\"company\":\"A\",\"title\":\"B\",\"startDate\":\"2022-01-01\",\"endDate\":\"2023-01-01\",\"isCurrent\":true}"
            },
            {"experience", "{\"company\":\"A\",\"title\":\"B\",\"startDate\":\"2022-01-01\",\"isCurrent\":false}"},
            {"cv/upload-url", "{\"contentType\":\"application/pdf\",\"size\":10485761}"}
        })
            mvc.perform(post("/api/employees/42/" + request[0])
                            .header("X-User-Id", "42")
                            .contentType("application/json")
                            .content(request[1]))
                    .andExpect(status().isBadRequest());
        verifyNoInteractions(cvs, sections);
    }
}
