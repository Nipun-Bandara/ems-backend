package com.ems.identity_service.service.impl;

import com.ems.identity_service.dto.request.AssignRoleAndDepartmentRequest;
import com.ems.identity_service.dto.response.UserResponse;
import com.ems.identity_service.entity.DepartmentEntity;
import com.ems.identity_service.entity.RoleEntity;
import com.ems.identity_service.entity.UserEntity;
import com.ems.identity_service.entity.UserRoles;
import com.ems.identity_service.repository.DepartmentRepository;
import com.ems.identity_service.repository.RoleRepository;
import com.ems.identity_service.repository.UserRepository;
import com.ems.identity_service.repository.UserRolesRepository;
import com.ems.identity_service.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final UserRolesRepository userRolesRepository;
    private final DepartmentRepository departmentRepository;

    @Override
    @Transactional(readOnly = true)
    public List<UserResponse> getUnassignedUsers() {
        List<UserEntity> unassignedUsers = userRepository.findByIsAssignedFalse();
        return unassignedUsers.stream()
                .map(this::convertToUserResponse)
                .collect(Collectors.toList());
    }


    @Override
    public UserResponse assignRoleAndDepartment(Long userId, AssignRoleAndDepartmentRequest request) {
        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found with ID: " + userId));

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        UserEntity authUser = userRepository.findByUsername(authentication.getName())
                .orElseThrow(() -> new IllegalArgumentException("Authenticated user not found"));

        boolean isSystemAdmin = authentication.getAuthorities().stream()
                .anyMatch(auth -> auth.getAuthority().equals("ROLE_SYSTEM_ADMIN"));

        boolean isDepartmentHead = authentication.getAuthorities().stream()
                .anyMatch(auth -> auth.getAuthority().equals("ROLE_DEPARTMENT_HEAD"));

        if (isSystemAdmin) {
            if (request.getRole() != null) {
                RoleEntity role = roleRepository.findByRoleName(request.getRole())
                        .orElseThrow(() -> new IllegalArgumentException("Role not found: " + request.getRole()));

                if (user.getUserRoles() != null && !user.getUserRoles().isEmpty()) {
                    userRolesRepository.deleteAll(user.getUserRoles());
                }

                UserRoles userRole = UserRoles.builder()
                        .user(user)
                        .role(role)
                        .assignedAt(LocalDateTime.now())
                        .build();

                userRolesRepository.save(userRole);
                user.setUserRoles(new ArrayList<>(List.of(userRole)));
            }

            if (request.getDepartmentId() != null) {
                DepartmentEntity department = departmentRepository.findById(request.getDepartmentId())
                        .orElseThrow(() -> new IllegalArgumentException("Department not found with ID: " + request.getDepartmentId()));
                user.setDepartment(department);
                user.setIsAssigned(true);
            }
        }
        
        else if (isDepartmentHead) {
            if (request.getRole() == null) {
                throw new IllegalArgumentException("Department Head can only assign role. Role field is required.");
            }

            if (authUser.getDepartment() == null) {
                throw new IllegalArgumentException("Department Head must have a department assigned to assign users.");
            }

            RoleEntity role = roleRepository.findByRoleName(request.getRole())
                    .orElseThrow(() -> new IllegalArgumentException("Role not found: " + request.getRole()));

            if (user.getUserRoles() != null && !user.getUserRoles().isEmpty()) {
                userRolesRepository.deleteAll(user.getUserRoles());
            }

            UserRoles userRole = UserRoles.builder()
                    .user(user)
                    .role(role)
                    .assignedAt(LocalDateTime.now())
                    .build();

            userRolesRepository.save(userRole);
            user.setUserRoles(new ArrayList<>(List.of(userRole)));
            user.setDepartment(authUser.getDepartment());
            user.setIsAssigned(true);
        } else {
            throw new IllegalArgumentException("User does not have permission to assign roles or departments.");
        }

        UserEntity updatedUser = userRepository.save(user);
        return convertToUserResponse(updatedUser);
    }

    @Override
    @Transactional(readOnly = true)
    public UserResponse getUserById(Long userId) {
        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found with ID: " + userId));
        return convertToUserResponse(user);
    }

    @Override
    @Transactional(readOnly = true)
    public List<UserResponse> getAllUsers() {
        List<UserEntity> users = userRepository.findAll();
        return users.stream()
                .map(this::convertToUserResponse)
                .collect(Collectors.toList());
    }

    private UserResponse convertToUserResponse(UserEntity user) {
        List<com.ems.identity_service.enums.Role> roles = user.getUserRoles() != null
                ? user.getUserRoles().stream()
                        .map(ur -> ur.getRole().getRoleName())
                        .collect(Collectors.toList())
                : List.of();

        return UserResponse.builder()
                .userId(user.getUserId())
                .username(user.getUsername())
                .email(user.getEmail())
                .departmentId(user.getDepartment() != null ? user.getDepartment().getDepartmentId() : null)
                .departmentName(user.getDepartment() != null ? user.getDepartment().getDepartmentName() : null)
                .isAssigned(user.getIsAssigned())
                .roles(roles)
                .isBanned(user.getIsBanned())
                .build();
    }
}
