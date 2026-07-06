package com.gying.movie.service.impl;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import java.io.ByteArrayInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

@Service
public class PosterStorageService {

    private static final Logger log = LoggerFactory.getLogger(PosterStorageService.class);
    private static final String TMDB_IMAGE_BASE_URL = "https://image.tmdb.org/t/p/w500";

    private final RestTemplate restTemplate;
    private final String endpoint;
    private final String accessKey;
    private final String secretKey;
    private final String bucket;
    private final boolean secure;

    private MinioClient minioClient;

    public PosterStorageService(
            RestTemplateBuilder restTemplateBuilder,
            @Value("${minio.endpoint:}") String endpoint,
            @Value("${minio.access-key:}") String accessKey,
            @Value("${minio.secret-key:}") String secretKey,
            @Value("${minio.bucket:gying}") String bucket,
            @Value("${minio.secure:false}") boolean secure) {
        this.restTemplate = restTemplateBuilder.build();
        this.endpoint = endpoint;
        this.accessKey = accessKey;
        this.secretKey = secretKey;
        this.bucket = bucket;
        this.secure = secure;
    }

    public String storeTmdbPoster(String mediaType, Long tmdbId, String posterPath) {
        if (!hasText(mediaType) || tmdbId == null || tmdbId <= 0 || !hasText(posterPath) || !isConfigured()) {
            return null;
        }
        String extension = extension(posterPath);
        String objectName = "tmdb/" + mediaType.trim().toLowerCase() + "/" + tmdbId + "/poster" + extension;
        try {
            ResponseEntity<byte[]> response = restTemplate.getForEntity(TMDB_IMAGE_BASE_URL + posterPath, byte[].class);
            byte[] body = response.getBody();
            if (!response.getStatusCode().is2xxSuccessful() || body == null || body.length == 0) {
                return null;
            }
            ensureBucket();
            try (ByteArrayInputStream input = new ByteArrayInputStream(body)) {
                client().putObject(PutObjectArgs.builder()
                        .bucket(bucket)
                        .object(objectName)
                        .stream(input, body.length, -1)
                        .contentType(contentType(extension))
                        .build());
            }
            return objectName;
        } catch (Exception e) {
            log.warn("Failed to store TMDB poster {}", objectName, e);
            return null;
        }
    }

    private void ensureBucket() throws Exception {
        boolean exists = client().bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
        if (!exists) {
            client().makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
        }
    }

    private MinioClient client() {
        if (minioClient == null) {
            String scheme = secure ? "https://" : "http://";
            String url = endpoint.startsWith("http://") || endpoint.startsWith("https://")
                    ? endpoint
                    : scheme + endpoint;
            minioClient = MinioClient.builder()
                    .endpoint(url)
                    .credentials(accessKey, secretKey)
                    .build();
        }
        return minioClient;
    }

    private boolean isConfigured() {
        return hasText(endpoint) && hasText(accessKey) && hasText(secretKey) && hasText(bucket);
    }

    private String extension(String posterPath) {
        int queryIndex = posterPath.indexOf('?');
        String cleanPath = queryIndex >= 0 ? posterPath.substring(0, queryIndex) : posterPath;
        int dotIndex = cleanPath.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == cleanPath.length() - 1) {
            return ".jpg";
        }
        String extension = cleanPath.substring(dotIndex).toLowerCase();
        return extension.length() <= 8 ? extension : ".jpg";
    }

    private String contentType(String extension) {
        return switch (extension) {
            case ".png" -> "image/png";
            case ".webp" -> "image/webp";
            case ".avif" -> "image/avif";
            default -> "image/jpeg";
        };
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
