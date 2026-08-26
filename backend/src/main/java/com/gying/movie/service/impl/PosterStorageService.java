package com.gying.movie.service.impl;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

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

    public String storeUploadedPoster(String movieId, MultipartFile file) {
        if (!hasText(movieId) || file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Image file is required");
        }
        if (file.getSize() > 8 * 1024 * 1024L) {
            throw new IllegalArgumentException("Image must be smaller than 8 MB");
        }
        if (!isConfigured()) throw new IllegalArgumentException("Image storage is not configured");
        String safeMovieId = movieId.replaceAll("[^A-Za-z0-9._-]", "_");
        try {
            byte[] body = file.getBytes();
            ImageFormat format = detectImageFormat(body);
            if (format == null) {
                throw new IllegalArgumentException("Only valid JPEG, PNG, WebP or AVIF images are supported");
            }
            String objectName = "manual/" + safeMovieId + "/poster-" + UUID.randomUUID() + format.extension();
            ensureBucket();
            try (ByteArrayInputStream input = new ByteArrayInputStream(body)) {
                client().putObject(PutObjectArgs.builder().bucket(bucket).object(objectName)
                        .stream(input, body.length, -1).contentType(format.contentType()).build());
            }
            return objectName;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Failed to store uploaded poster for movie {}", movieId, e);
            throw new IllegalArgumentException("Failed to store image");
        }
    }

    private ImageFormat detectImageFormat(byte[] body) {
        if (body.length >= 3 && (body[0] & 0xff) == 0xff && (body[1] & 0xff) == 0xd8
                && (body[2] & 0xff) == 0xff) {
            return new ImageFormat(".jpg", "image/jpeg");
        }
        byte[] pngSignature = {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
        if (body.length >= pngSignature.length
                && Arrays.equals(Arrays.copyOf(body, pngSignature.length), pngSignature)) {
            return new ImageFormat(".png", "image/png");
        }
        if (body.length >= 12 && ascii(body, 0, 4).equals("RIFF") && ascii(body, 8, 4).equals("WEBP")) {
            return new ImageFormat(".webp", "image/webp");
        }
        if (body.length >= 12 && ascii(body, 4, 4).equals("ftyp")) {
            String brand = ascii(body, 8, 4);
            if ("avif".equals(brand) || "avis".equals(brand)) {
                return new ImageFormat(".avif", "image/avif");
            }
        }
        return null;
    }

    private String ascii(byte[] body, int offset, int length) {
        return new String(body, offset, length, StandardCharsets.US_ASCII);
    }

    private record ImageFormat(String extension, String contentType) {
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
