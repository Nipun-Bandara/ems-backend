package com.ems.employee_service.entity;

import com.ems.employee_service.dto.EmployeeSkillRequest;
import jakarta.persistence.*;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "employee_skill")
@Getter
@NoArgsConstructor
public class EmployeeSkill {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false)
    private Long userId;

    @Column(length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    private Proficiency proficiency;

    @Column(precision = 4, scale = 1)
    private BigDecimal yearsOfExperience;

    public EmployeeSkill(Long userId, EmployeeSkillRequest request) {
        this.userId = userId;
        update(request);
    }

    public void update(EmployeeSkillRequest r) {
        name = r.name();
        proficiency = r.proficiency();
        yearsOfExperience = r.yearsOfExperience();
    }
}
