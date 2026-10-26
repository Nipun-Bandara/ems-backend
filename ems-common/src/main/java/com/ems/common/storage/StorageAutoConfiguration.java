package com.ems.common.storage;

import java.net.URI;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@AutoConfiguration
@ConditionalOnProperty(name = "ems.storage.enabled", havingValue = "true")
@EnableConfigurationProperties(StorageProperties.class)
public class StorageAutoConfiguration {
    @Bean
    AwsCredentialsProvider storageCredentials(StorageProperties p) {
        if (p.accessKey() != null && !p.accessKey().isBlank()) {
            return StaticCredentialsProvider.create(AwsBasicCredentials.create(p.accessKey(), p.secretKey()));
        }
        return DefaultCredentialsProvider.create();
    }

    @Bean(destroyMethod = "close")
    S3Client storageClient(StorageProperties p, AwsCredentialsProvider credentials) {
        var builder = S3Client.builder()
                .region(Region.of(p.region()))
                .credentialsProvider(credentials)
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(p.pathStyleAccess())
                        .build());
        if (p.endpoint() != null && !p.endpoint().isBlank()) builder.endpointOverride(URI.create(p.endpoint()));
        return builder.build();
    }

    @Bean(destroyMethod = "close")
    S3Presigner storagePresigner(StorageProperties p, AwsCredentialsProvider credentials) {
        var builder = S3Presigner.builder()
                .region(Region.of(p.region()))
                .credentialsProvider(credentials)
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(p.pathStyleAccess())
                        .build());
        String endpoint =
                p.publicEndpoint() == null || p.publicEndpoint().isBlank() ? p.endpoint() : p.publicEndpoint();
        if (endpoint != null && !endpoint.isBlank()) builder.endpointOverride(URI.create(endpoint));
        return builder.build();
    }

    @Bean
    StorageService storageService(S3Client client, S3Presigner presigner) {
        return new StorageService(client, presigner);
    }

    @Bean
    BucketBootstrap bucketBootstrap(S3Client client, StorageProperties properties) {
        return new BucketBootstrap(client, properties);
    }
}
