package com.ems.common.storage;

import java.time.Duration;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.*;

/** Object bytes travel directly between the browser and object storage. */
public class StorageService {
    private final S3Client client;
    private final S3Presigner presigner;

    public StorageService(S3Client client, S3Presigner presigner) {
        this.client = client;
        this.presigner = presigner;
    }

    public String presignedUpload(String bucket, String key, String contentType, Duration ttl) {
        return sign(
                PutObjectRequest.builder()
                        .bucket(bucket)
                        .key(key)
                        .contentType(contentType)
                        .build(),
                ttl);
    }

    /** Exact length and create-only writes prevent oversized uploads and later replacement. */
    public String presignedUpload(String bucket, String key, String contentType, long size, Duration ttl) {
        return sign(
                PutObjectRequest.builder()
                        .bucket(bucket)
                        .key(key)
                        .contentType(contentType)
                        .contentLength(size)
                        .overrideConfiguration(c -> c.putHeader("If-None-Match", "*"))
                        .build(),
                ttl);
    }

    private String sign(PutObjectRequest request, Duration ttl) {
        return presigner
                .presignPutObject(PutObjectPresignRequest.builder()
                        .signatureDuration(ttl)
                        .putObjectRequest(request)
                        .build())
                .url()
                .toString();
    }

    public String presignedDownload(String bucket, String key, Duration ttl) {
        return presigner
                .presignGetObject(GetObjectPresignRequest.builder()
                        .signatureDuration(ttl)
                        .getObjectRequest(r -> r.bucket(bucket).key(key))
                        .build())
                .url()
                .toString();
    }

    public HeadObjectResponse head(String bucket, String key) {
        return client.headObject(r -> r.bucket(bucket).key(key));
    }

    public void delete(String bucket, String key) {
        client.deleteObject(r -> r.bucket(bucket).key(key));
    }
}
