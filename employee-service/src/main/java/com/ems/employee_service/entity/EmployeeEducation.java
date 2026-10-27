package com.ems.employee_service.entity;

import com.ems.employee_service.dto.EmployeeEducationRequest;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "employee_education")
@Getter
@NoArgsConstructor
public class EmployeeEducation {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false)
    private Long userId;

    @Column(length = 200)
    private String institution;

    @Column(length = 200)
    private String qualification;

    @Column(length = 200)
    private String fieldOfStudy;

    private Integer startYear;
    private Integer endYear;

    @Column(length = 100)
    private String grade;

    public EmployeeEducation(Long userId, EmployeeEducationRequest request) {
        this.userId = userId;
        update(request);
    }

    public void update(EmployeeEducationRequest r) {
        institution = r.institution();
        qualification = r.qualification();
        fieldOfStudy = r.fieldOfStudy();
        startYear = r.startYear();
        endYear = r.endYear();
        grade = r.grade();
    }
}
