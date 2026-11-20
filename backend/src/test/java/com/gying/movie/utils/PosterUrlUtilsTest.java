package com.gying.movie.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class PosterUrlUtilsTest {

    @Test
    void buildsAndKeepsPublicUrlIdempotent() {
        String objectKey = "tmdb/movie/24428/poster.jpg";
        String publicUrl = "/media/tmdb/movie/24428/poster.jpg";

        assertEquals(publicUrl, PosterUrlUtils.toPublicUrl(objectKey, "/media/"));
        assertEquals(publicUrl, PosterUrlUtils.toPublicUrl(publicUrl, "/media/"));
        assertEquals(publicUrl, PosterUrlUtils.toPublicUrl("/media/media/tmdb/movie/24428/poster.jpg", "/media/"));
    }

    @Test
    void convertsPublicUrlBackToObjectKey() {
        assertEquals("tmdb/movie/24428/poster.jpg",
                PosterUrlUtils.toStoragePath("/media/tmdb/movie/24428/poster.jpg", "/media/"));
        assertEquals("tmdb/movie/24428/poster.jpg",
                PosterUrlUtils.toStoragePath("/media/media/tmdb/movie/24428/poster.jpg", "/media/"));
        assertEquals("tmdb/movie/24428/poster.jpg",
                PosterUrlUtils.toStoragePath("tmdb/movie/24428/poster.jpg", "/media/"));
    }

    @Test
    void preservesExternalUrls() {
        String externalUrl = "https://image.example.com/poster.jpg";

        assertEquals(externalUrl, PosterUrlUtils.toStoragePath(externalUrl, "/media/"));
        assertEquals(externalUrl, PosterUrlUtils.toPublicUrl(externalUrl, "/media/"));
        assertEquals("https://localhost:9000/poster.jpg",
                PosterUrlUtils.toPublicUrl("https://host.docker.internal:9000/poster.jpg", "/media/"));
    }

    @Test
    void supportsFullPublicUrlPrefix() {
        String prefix = "https://image.example.com/media";

        assertEquals(prefix + "/tmdb/movie/24428/poster.jpg",
                PosterUrlUtils.toPublicUrl("tmdb/movie/24428/poster.jpg", prefix));
        assertEquals("tmdb/movie/24428/poster.jpg",
                PosterUrlUtils.toStoragePath(prefix + "/tmdb/movie/24428/poster.jpg", prefix));
        assertEquals("tmdb/movie/24428/poster.jpg",
                PosterUrlUtils.toStoragePath("/media/tmdb/movie/24428/poster.jpg", prefix));
    }

    @Test
    void blankPosterClearsStoredValue() {
        assertNull(PosterUrlUtils.toStoragePath(null, "/media/"));
        assertNull(PosterUrlUtils.toStoragePath("   ", "/media/"));
        assertNull(PosterUrlUtils.toStoragePath("/media/", "/media/"));
    }
}
