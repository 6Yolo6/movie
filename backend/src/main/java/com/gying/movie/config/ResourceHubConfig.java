package com.gying.movie.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ResourceHubProperties.class)
public class ResourceHubConfig {
}
