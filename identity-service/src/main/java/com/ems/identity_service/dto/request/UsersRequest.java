package com.ems.identity_service.dto.request;

import lombok.Data;

@Data
public class UsersRequest {
    private Boolean assigned = true;
    private int page;
    private int limit;

}
