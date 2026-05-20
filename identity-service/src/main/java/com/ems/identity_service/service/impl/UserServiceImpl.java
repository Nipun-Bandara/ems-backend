package com.ems.identity_service.service.impl;

import com.ems.identity_service.dto.request.AssignRoleAndDepartmentRequest;
import com.ems.identity_service.dto.request.UsersRequest;
import com.ems.identity_service.dto.response.PaginatedUserResponse;
import com.ems.identity_service.dto.response.UserRecord;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
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
    public UserRecord assignRoleAndDepartment(Long userId, AssignRoleAndDepartmentRequest request) {
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
        } else if (isDepartmentHead) {
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
        return convertToUserRecord(updatedUser);
    }

    @Override
    @Transactional(readOnly = true)
    public UserRecord getUserById(Long userId) {
        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found with ID: " + userId));
        return convertToUserRecord(user);
    }

    @Override
    @Transactional(readOnly = true)
    public PaginatedUserResponse getUsers(UsersRequest usersRequest) {

        Pageable pageable = PageRequest.of(usersRequest.getPage(), usersRequest.getLimit());
        if (usersRequest.getAssigned()) {

            Page<UserEntity> usersPage = userRepository.findAll(pageable);

            List<UserRecord> users = usersPage.getContent().stream()
                    .map(this::convertToUserRecord)
                    .collect(Collectors.toList());

            return PaginatedUserResponse.builder()
                    .users(users)
                    .hasNext(usersPage.hasNext())
                    .hasPrevious(usersPage.hasPrevious())
                    .build();

        } else {
            Page<UserEntity> assignedUsersPage = userRepository.findByIsAssignedFalse(pageable);
            List<UserRecord> users = assignedUsersPage.getContent().stream()
                    .map(this::convertToUserRecord)
                    .collect(Collectors.toList());
            return PaginatedUserResponse.builder()
                    .users(users)
                    .hasNext(assignedUsersPage.hasNext())
                    .hasPrevious(assignedUsersPage.hasPrevious())
                    .build();
        }

    }

    private UserRecord convertToUserRecord(UserEntity user) {
        List<com.ems.identity_service.enums.Role> roles = user.getUserRoles() != null
                ? user.getUserRoles().stream()
                .map(ur -> ur.getRole().getRoleName())
                .collect(Collectors.toList())
                : List.of();

        return UserRecord.builder()
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
