package com.ems.employee_service.controller;

import com.ems.employee_service.dto.*;
import com.ems.employee_service.entity.*;
import com.ems.employee_service.service.EmployeeCvSectionsService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/employees/{userId}")
@PreAuthorize("#userId.toString() == authentication.name or hasAnyRole('SYSTEM_ADMIN', 'HR_MANAGER')")
public class EmployeeCvSectionsController {
    private final EmployeeCvSectionsService sections;

    @GetMapping("/skills")
    public List<EmployeeSkill> listSkill(@PathVariable @Positive Long userId) {
        return sections.listSkill(userId);
    }

    @PostMapping("/skills")
    @ResponseStatus(HttpStatus.CREATED)
    public EmployeeSkill createSkill(
            @PathVariable @Positive Long userId, @Valid @RequestBody EmployeeSkillRequest request) {
        return sections.createSkill(userId, request);
    }

    @PutMapping("/skills/{id}")
    public EmployeeSkill updateSkill(
            @PathVariable @Positive Long userId,
            @PathVariable @Positive Long id,
            @Valid @RequestBody EmployeeSkillRequest request) {
        return sections.updateSkill(userId, id, request);
    }

    @DeleteMapping("/skills/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteSkill(@PathVariable @Positive Long userId, @PathVariable @Positive Long id) {
        sections.deleteSkill(userId, id);
    }

    @GetMapping("/education")
    public List<EmployeeEducation> listEducation(@PathVariable @Positive Long userId) {
        return sections.listEducation(userId);
    }

    @PostMapping("/education")
    @ResponseStatus(HttpStatus.CREATED)
    public EmployeeEducation createEducation(
            @PathVariable @Positive Long userId, @Valid @RequestBody EmployeeEducationRequest request) {
        return sections.createEducation(userId, request);
    }

    @PutMapping("/education/{id}")
    public EmployeeEducation updateEducation(
            @PathVariable @Positive Long userId,
            @PathVariable @Positive Long id,
            @Valid @RequestBody EmployeeEducationRequest request) {
        return sections.updateEducation(userId, id, request);
    }

    @DeleteMapping("/education/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteEducation(@PathVariable @Positive Long userId, @PathVariable @Positive Long id) {
        sections.deleteEducation(userId, id);
    }

    @GetMapping("/experience")
    public List<EmployeeExperience> listExperience(@PathVariable @Positive Long userId) {
        return sections.listExperience(userId);
    }

    @PostMapping("/experience")
    @ResponseStatus(HttpStatus.CREATED)
    public EmployeeExperience createExperience(
            @PathVariable @Positive Long userId, @Valid @RequestBody EmployeeExperienceRequest request) {
        return sections.createExperience(userId, request);
    }

    @PutMapping("/experience/{id}")
    public EmployeeExperience updateExperience(
            @PathVariable @Positive Long userId,
            @PathVariable @Positive Long id,
            @Valid @RequestBody EmployeeExperienceRequest request) {
        return sections.updateExperience(userId, id, request);
    }

    @DeleteMapping("/experience/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteExperience(@PathVariable @Positive Long userId, @PathVariable @Positive Long id) {
        sections.deleteExperience(userId, id);
    }
}
