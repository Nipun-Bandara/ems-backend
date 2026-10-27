package com.ems.employee_service.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.ems.common.storage.StorageService;
import com.ems.employee_service.entity.EmployeeProfile;
import com.ems.employee_service.repository.EmployeeProfileRepository;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;

class EmployeeCvServiceTest {
    EmployeeProfileRepository repository = mock(EmployeeProfileRepository.class);
    StorageService storage = mock(StorageService.class);
    EmployeeCvService service =
            new EmployeeCvService(repository, storage, mock(com.ems.common.outbox.OutboxPublisher.class));
    EmployeeProfile profile = new EmployeeProfile();

    @BeforeEach
    void setup() {
        when(repository.findForUpdate(42L)).thenReturn(Optional.of(profile));
        when(repository.findById(42L)).thenReturn(Optional.of(profile));
    }

    @Test
    void rejectsUnsupportedTypesAndSizesBeforeSigning() {
        assertThrows(ResponseStatusException.class, () -> service.requestUpload(42L, "image/svg+xml", 10));
        assertThrows(ResponseStatusException.class, () -> service.requestUpload(42L, "application/pdf", 10485761));
        assertThrows(ResponseStatusException.class, () -> service.requestUpload(42L, "application/pdf", 0));
        verifyNoInteractions(storage);
    }

    @Test
    void acceptsOnlyAnIssuedUnexpiredKeyForThisEmployee() {
        profile.requestCv("issued", "application/pdf", 10, Instant.now().plusSeconds(300));
        assertThrows(ResponseStatusException.class, () -> service.confirmUpload(42L, "someone-elses-key"));
        profile.requestCv("issued", "application/pdf", 10, Instant.now().minusSeconds(1));
        assertThrows(ResponseStatusException.class, () -> service.confirmUpload(42L, "issued"));
        verifyNoInteractions(storage);
    }

    @Test
    void deletesInvalidObjectWithoutRecordingIt() {
        profile.requestCv("issued", "application/pdf", 10, Instant.now().plusSeconds(300));
        when(storage.head("ems-documents", "issued"))
                .thenReturn(HeadObjectResponse.builder()
                        .contentLength(11L)
                        .contentType("application/pdf")
                        .build());
        assertThrows(ResponseStatusException.class, () -> service.confirmUpload(42L, "issued"));
        assertNull(profile.getCvObjectKey());
        verify(storage).delete("ems-documents", "issued");
    }

    @Test
    void confirmsVerifiedObjectAndReturnsOnlySignedUrl() {
        profile.requestCv("issued", "application/pdf", 10, Instant.now().plusSeconds(300));
        when(storage.head("ems-documents", "issued"))
                .thenReturn(HeadObjectResponse.builder()
                        .contentLength(10L)
                        .contentType("application/pdf")
                        .build());
        when(storage.presignedDownload(eq("ems-documents"), eq("issued"), any()))
                .thenReturn("https://signed-download");
        assertEquals(
                "https://signed-download", service.confirmUpload(42L, "issued").downloadUrl());
        assertEquals("issued", profile.getCvObjectKey());
        assertNull(profile.getPendingCvKey());
        assertThrows(ResponseStatusException.class, () -> service.confirmUpload(42L, "issued"));
        assertEquals("https://signed-download", service.getCv(42L).downloadUrl());
    }
}
