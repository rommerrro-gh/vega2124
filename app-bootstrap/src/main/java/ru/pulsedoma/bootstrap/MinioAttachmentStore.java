package ru.pulsedoma.bootstrap;

import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import ru.pulsedoma.issues.AttachmentStore;

import java.io.ByteArrayInputStream;

@Component
@Profile("!demo")
public class MinioAttachmentStore implements AttachmentStore {
    private final MinioClient minio;
    private final String bucket;

    public MinioAttachmentStore(@Value("${storage.minio.endpoint}") String endpoint,
                                @Value("${storage.minio.access-key}") String accessKey,
                                @Value("${storage.minio.secret-key}") String secretKey,
                                @Value("${storage.minio.bucket:pulse-doma-attachments}") String bucket) {
        this.minio = MinioClient.builder().endpoint(endpoint).credentials(accessKey, secretKey).build();
        this.bucket = bucket;
    }

    @Override
    public void put(String key, byte[] data, String mime) {
        try {
            if (!minio.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
                minio.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
            }
            minio.putObject(PutObjectArgs.builder().bucket(bucket).object(key)
                    .stream(new ByteArrayInputStream(data), data.length, -1).contentType(mime).build());
        } catch (Exception e) {
            throw new IllegalStateException("Could not save attachment", e);
        }
    }

    @Override
    public byte[] get(String key) {
        try (var stream = minio.getObject(GetObjectArgs.builder().bucket(bucket).object(key).build())) {
            return stream.readAllBytes();
        } catch (Exception e) {
            throw new IllegalStateException("Could not read attachment", e);
        }
    }
}
