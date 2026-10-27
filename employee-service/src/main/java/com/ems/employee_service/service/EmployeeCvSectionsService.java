package com.ems.employee_service.service;

import com.ems.employee_service.dto.*;
import com.ems.employee_service.entity.*;
import com.ems.employee_service.repository.*;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
@Transactional
public class EmployeeCvSectionsService {
    private final EmployeeProfileRepository profiles;
    private final EmployeeSkillRepository skills;
    private final EmployeeEducationRepository educations;
    private final EmployeeExperienceRepository experiences;

    private void requireProfile(Long userId) {
        if (!profiles.existsById(userId))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Employee profile not found");
    }

    @Transactional(readOnly = true)
    public List<EmployeeSkill> listSkill(Long userId) {
        requireProfile(userId);
        return skills.findByUserIdOrderByIdAsc(userId);
    }

    public EmployeeSkill createSkill(Long userId, EmployeeSkillRequest request) {
        requireProfile(userId);
        return skills.save(new EmployeeSkill(userId, request));
    }

    public EmployeeSkill updateSkill(Long userId, Long id, EmployeeSkillRequest request) {
        var entry = skills.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Entry not found"));
        entry.update(request);
        return entry;
    }

    public void deleteSkill(Long userId, Long id) {
        var entry = skills.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Entry not found"));
        skills.delete(entry);
    }

    @Transactional(readOnly = true)
    public List<EmployeeEducation> listEducation(Long userId) {
        requireProfile(userId);
        return educations.findByUserIdOrderByIdAsc(userId);
    }

    public EmployeeEducation createEducation(Long userId, EmployeeEducationRequest request) {
        requireProfile(userId);
        return educations.save(new EmployeeEducation(userId, request));
    }

    public EmployeeEducation updateEducation(Long userId, Long id, EmployeeEducationRequest request) {
        var entry = educations
                .findByIdAndUserId(id, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Entry not found"));
        entry.update(request);
        return entry;
    }

    public void deleteEducation(Long userId, Long id) {
        var entry = educations
                .findByIdAndUserId(id, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Entry not found"));
        educations.delete(entry);
    }

    @Transactional(readOnly = true)
    public List<EmployeeExperience> listExperience(Long userId) {
        requireProfile(userId);
        return experiences.findByUserIdOrderByIdAsc(userId);
    }

    public EmployeeExperience createExperience(Long userId, EmployeeExperienceRequest request) {
        requireProfile(userId);
        return experiences.save(new EmployeeExperience(userId, request));
    }

    public EmployeeExperience updateExperience(Long userId, Long id, EmployeeExperienceRequest request) {
        var entry = experiences
                .findByIdAndUserId(id, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Entry not found"));
        entry.update(request);
        return entry;
    }

    public void deleteExperience(Long userId, Long id) {
        var entry = experiences
                .findByIdAndUserId(id, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Entry not found"));
        experiences.delete(entry);
    }
}
