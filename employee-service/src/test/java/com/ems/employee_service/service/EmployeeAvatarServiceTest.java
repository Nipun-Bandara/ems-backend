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

class EmployeeAvatarServiceTest {
    EmployeeProfileRepository repository = mock(EmployeeProfileRepository.class);
    StorageService storage = mock(StorageService.class);
    EmployeeAvatarService service = new EmployeeAvatarService(repository, storage);
    EmployeeProfile profile = new EmployeeProfile();

    @BeforeEach
    void setup() {
        when(repository.findForUpdate(42L)).thenReturn(Optional.of(profile));
        when(repository.findById(42L)).thenReturn(Optional.of(profile));
    }

    @Test
    void rejectsUnsupportedTypesAndSizesBeforeSigning() {
        assertThrows(ResponseStatusException.class, () -> service.requestUpload(42L, "image/svg+xml", 10));
        assertThrows(ResponseStatusException.class, () -> service.requestUpload(42L, "image/png", 5242881));
        assertThrows(ResponseStatusException.class, () -> service.requestUpload(42L, "image/png", 0));
        verifyNoInteractions(storage);
    }

    @Test
    void acceptsOnlyAnIssuedUnexpiredKeyForThisEmployee() {
        profile.requestAvatar("issued", "image/png", 10, Instant.now().plusSeconds(300));
        assertThrows(ResponseStatusException.class, () -> service.confirmUpload(42L, "someone-elses-key"));
        profile.requestAvatar("issued", "image/png", 10, Instant.now().minusSeconds(1));
        assertThrows(ResponseStatusException.class, () -> service.confirmUpload(42L, "issued"));
        verifyNoInteractions(storage);
    }

    @Test
    void deletesInvalidObjectWithoutRecordingIt() {
        profile.requestAvatar("issued", "image/png", 10, Instant.now().plusSeconds(300));
        when(storage.head("ems-avatars", "issued"))
                .thenReturn(HeadObjectResponse.builder()
                        .contentLength(11L)
                        .contentType("image/png")
                        .build());
        assertThrows(ResponseStatusException.class, () -> service.confirmUpload(42L, "issued"));
        assertNull(profile.getAvatarObjectKey());
        verify(storage).delete("ems-avatars", "issued");
    }

    @Test
    void confirmsVerifiedObjectAndReturnsOnlySignedUrl() {
        profile.requestAvatar("issued", "image/png", 10, Instant.now().plusSeconds(300));
        when(storage.head("ems-avatars", "issued"))
                .thenReturn(HeadObjectResponse.builder()
                        .contentLength(10L)
                        .contentType("image/png")
                        .build());
        when(storage.presignedDownload(eq("ems-avatars"), eq("issued"), any())).thenReturn("https://signed-download");
        assertEquals(
                "https://signed-download", service.confirmUpload(42L, "issued").avatarUrl());
        assertEquals("issued", profile.getAvatarObjectKey());
        assertNull(profile.getPendingAvatarKey());
        assertThrows(ResponseStatusException.class, () -> service.confirmUpload(42L, "issued"));
        assertEquals("https://signed-download", service.getProfile(42L).avatarUrl());
    }
}
