package com.gying.movie.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gying.movie.config.ResourceHubProperties;
import com.gying.movie.utils.SeasonSearchUtils;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;

class QuarkAutoSaveClientTest {

    @Test
    void includesSeasonSubdirectoryPatternInTaskPayload() {
        ResourceHubProperties properties = new ResourceHubProperties();
        QuarkAutoSaveClient client = new QuarkAutoSaveClient(
                new RestTemplateBuilder(),
                new ObjectMapper(),
                properties);
        String pattern = SeasonSearchUtils.subdirectoryPattern(1);

        Map<String, Object> payload = client.buildTaskPayload(
                "The Rookie Season 1",
                "https://pan.quark.cn/s/source",
                "/GYing Resource Hub/tv/The Rookie Season 1",
                pattern);

        assertEquals(pattern, payload.get("update_subdir"));
        assertEquals("/GYing Resource Hub/tv/The Rookie Season 1", payload.get("savepath"));
    }
}