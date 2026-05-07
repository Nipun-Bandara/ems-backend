package com.ems.identity_service.service;

import com.ems.identity_service.dto.request.AssignRoleAndDepartmentRequest;
import com.ems.identity_service.dto.response.UserResponse;

import java.util.List;

public interface UserService {
    
    List<UserResponse> getUnassignedUsers();

    UserResponse assignRoleAndDepartment(Long userId, AssignRoleAndDepartmentRequest request);
    
    UserResponse getUserById(Long userId);
    
    List<UserResponse> getAllUsers();
}
