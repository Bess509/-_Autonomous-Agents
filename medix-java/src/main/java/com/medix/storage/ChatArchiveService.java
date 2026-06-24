package com.medix.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medix.config.MedixProperties;
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Service;

@Service
public class ChatArchiveService {
    private static final String BUCKET = "medix-answers";

    private final MedixProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ChatArchiveService(MedixProperties properties) {
        this.properties = properties;
    }

    public void archive(ChatArchive archive) {
        if (!properties.features().minio()) {
            return;
        }
        try {
            MinioClient client = MinioClient.builder()
                    .endpoint(properties.services().minioEndpoint())
                    .credentials(properties.services().minioAccessKey(), properties.services().minioSecretKey())
                    .build();
            if (!client.bucketExists(BucketExistsArgs.builder().bucket(BUCKET).build())) {
                client.makeBucket(MakeBucketArgs.builder().bucket(BUCKET).build());
            }
            byte[] payload = objectMapper.writeValueAsString(archive).getBytes(StandardCharsets.UTF_8);
            String objectName = archive.sessionId() + "/" + archive.createdAt().toEpochMilli() + ".json";
            client.putObject(PutObjectArgs.builder()
                    .bucket(BUCKET)
                    .object(objectName)
                    .contentType("application/json")
                    .stream(new ByteArrayInputStream(payload), payload.length, -1)
                    .build());
        } catch (Exception ignored) {
            // Archiving should not block the medical safety response path.
        }
    }
}
