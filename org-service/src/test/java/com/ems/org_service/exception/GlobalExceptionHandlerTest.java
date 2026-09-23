package com.ems.org_service.exception;

import static org.assertj.core.api.Assertions.assertThat;

import com.ems.common.error.ErrorResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

class GlobalExceptionHandlerTest {

    @Test
    void conflictExceptionProducesClear409Response() {
        MockHttpServletRequest request = new MockHttpServletRequest("DELETE", "/api/departments/12");

        ResponseEntity<ErrorResponse> response = new GlobalExceptionHandler()
                .handleConflict(
                        new ConflictException("Cannot delete department with ID 12 because it still has designations"),
                        request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message())
                .isEqualTo("Cannot delete department with ID 12 because it still has designations");
    }
}
