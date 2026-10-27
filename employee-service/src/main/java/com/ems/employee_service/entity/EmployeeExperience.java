package com.ems.employee_service.entity;

import com.ems.employee_service.dto.EmployeeExperienceRequest;
import jakarta.persistence.*;
import java.time.LocalDate;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "employee_experience")
@Getter
@NoArgsConstructor
public class EmployeeExperience {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false)
    private Long userId;

    @Column(length = 200)
    private String company;

    @Column(length = 200)
    private String title;

    private LocalDate startDate;
    private LocalDate endDate;

    @Column(length = 5000)
    private String description;

    private Boolean isCurrent;

    public EmployeeExperience(Long userId, EmployeeExperienceRequest request) {
        this.userId = userId;
        update(request);
    }

    public void update(EmployeeExperienceRequest r) {
        company = r.company();
        title = r.title();
        startDate = r.startDate();
        endDate = r.endDate();
        description = r.description();
        isCurrent = r.isCurrent();
    }
}
