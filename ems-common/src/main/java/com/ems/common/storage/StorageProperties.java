package com.ems.common.storage;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("ems.storage")
public record StorageProperties(
        String endpoint,
        String publicEndpoint,
        String region,
        String accessKey,
        String secretKey,
        boolean pathStyleAccess,
        List<String> allowedOrigins) {}
