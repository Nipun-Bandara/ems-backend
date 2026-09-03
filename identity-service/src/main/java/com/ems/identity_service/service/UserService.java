package com.ems.identity_service.service;

import com.ems.identity_service.dto.request.AssignRoleAndDepartmentRequest;
import com.ems.identity_service.dto.response.PaginatedUserResponse;
import com.ems.identity_service.dto.response.UserRecord;

public interface UserService {

    UserRecord assignRoleAndDepartment(Long userId, AssignRoleAndDepartmentRequest request);

    UserRecord getUserById(Long userId);

    PaginatedUserResponse getUsers(boolean assigned, int page, int limit);
}
