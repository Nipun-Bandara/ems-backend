package com.ems.common.storage;

import java.util.List;
import org.springframework.beans.factory.InitializingBean;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

public class BucketBootstrap implements InitializingBean {
    private final S3Client client;
    private final StorageProperties properties;

    public BucketBootstrap(S3Client client, StorageProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    @Override
    public void afterPropertiesSet() {
        for (String bucket : List.of("ems-avatars", "ems-documents", "ems-attachments")) {
            try {
                client.headBucket(r -> r.bucket(bucket));
            } catch (S3Exception e) {
                if (e.statusCode() != 404) throw e;
                try {
                    client.createBucket(r -> {
                        r.bucket(bucket);
                        String region =
                                client.serviceClientConfiguration().region().id();
                        if (!"us-east-1".equals(region)) r.createBucketConfiguration(c -> c.locationConstraint(region));
                    });
                } catch (BucketAlreadyOwnedByYouException ignored) {
                    // Another service instance completed the bootstrap concurrently.
                }
            }
            // MinIO handles S3 CORS globally; AWS S3 uses this per-bucket rule.
            if ((properties.endpoint() == null || properties.endpoint().isBlank())
                    && properties.allowedOrigins() != null
                    && !properties.allowedOrigins().isEmpty()) {
                client.putBucketCors(r -> r.bucket(bucket)
                        .corsConfiguration(c -> c.corsRules(rule -> rule.allowedOrigins(properties.allowedOrigins())
                                .allowedMethods("PUT", "GET", "HEAD")
                                .allowedHeaders("Content-Type", "If-None-Match")
                                .exposeHeaders("ETag")
                                .maxAgeSeconds(3600))));
            }
        }
    }
}
