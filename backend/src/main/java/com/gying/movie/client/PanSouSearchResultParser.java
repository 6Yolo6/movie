package com.gying.movie.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.gying.movie.dto.DiscoveredResource;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

final class PanSouSearchResultParser {

    private static final Set<String> SUPPORTED_PROVIDERS = Set.of(
            "QUARK", "BAIDU", "ALIYUN", "UC", "XUNLEI",
            "115", "123PAN", "PIKPAK", "TIANYI", "MOBILE");

    private PanSouSearchResultParser() {
    }

    static List<DiscoveredResource> parse(JsonNode root, int maxResults) {
        return parseProviders(root, maxResults, Set.of("QUARK"));
    }

    static List<DiscoveredResource> parseOtherClouds(JsonNode root, int maxResults) {
        return parseProviders(root, maxResults, otherCloudProviders());
    }

    static List<DiscoveredResource> parseProviders(
            JsonNode root,
            int maxResults,
            Set<String> acceptedProviders) {
        List<DiscoveredResource> results = new ArrayList<>();
        Set<String> providers = acceptedProviders == null ? Set.of() : acceptedProviders;
        collectResources(root.path("data").path("list"), results, maxResults, null, null, providers);
        collectResources(root.path("data").path("results"), results, maxResults, null, null, providers);
        collectResources(root.path("list"), results, maxResults, null, null, providers);
        collectResources(root.path("results"), results, maxResults, null, null, providers);

        JsonNode merged = root.path("data").path("merged_by_type");
        if (merged.isObject()) {
            Iterator<JsonNode> values = merged.elements();
            while (values.hasNext() && results.size() < maxResults) {
                collectResources(values.next(), results, maxResults, null, null, providers);
            }
        }
        return results;
    }

    static Set<String> otherCloudProviders() {
        Set<String> providers = new java.util.LinkedHashSet<>(SUPPORTED_PROVIDERS);
        providers.remove("QUARK");
        return providers;
    }

    private static void collectResources(
            JsonNode node,
            List<DiscoveredResource> results,
            int maxResults,
            String parentTitle,
            String parentRef,
            Set<String> acceptedProviders) {
        if (node == null || node.isMissingNode() || node.isNull() || results.size() >= maxResults) {
            return;
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                collectResources(item, results, maxResults, parentTitle, parentRef, acceptedProviders);
                if (results.size() >= maxResults) {
                    break;
                }
            }
            return;
        }
        if (!node.isObject()) {
            return;
        }
        String currentTitle = firstText(
                node.path("work_title").asText(null),
                node.path("title").asText(null),
                node.path("name").asText(null),
                node.path("filename").asText(null),
                node.path("note").asText(null),
                parentTitle);
        String currentRef = firstText(
                node.path("unique_id").asText(null),
                node.path("message_id").asText(null),
                node.path("id").asText(null),
                node.path("source_id").asText(null),
                node.path("source").asText(null),
                node.path("channel").asText(null),
                node.path("datetime").asText(null),
                parentRef);
        String url = firstText(
                node.path("url").asText(null),
                node.path("link").asText(null),
                node.path("share_url").asText(null),
                node.path("shareUrl").asText(null));
        String provider = providerForUrl(url);
        if (provider != null && acceptedProviders.contains(provider)) {
            DiscoveredResource resource = new DiscoveredResource();
            resource.setTitle(firstText(currentTitle, url));
            resource.setProvider(provider);
            resource.setUrl(url.trim());
            resource.setCode(firstText(
                    node.path("password").asText(null),
                    node.path("pwd").asText(null),
                    node.path("code").asText(null)));
            resource.setSource("PANSOU");
            resource.setSourceRef(currentRef);
            results.add(resource);
        }

        collectResources(node.path("links"), results, maxResults, currentTitle, currentRef, acceptedProviders);
        collectResources(node.path("items"), results, maxResults, currentTitle, currentRef, acceptedProviders);
        collectResources(node.path("resources"), results, maxResults, currentTitle, currentRef, acceptedProviders);
    }

    private static String providerForUrl(String value) {
        if (!hasText(value)) {
            return null;
        }
        String lower = value.trim().toLowerCase();
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            return null;
        }
        if (lower.contains("pan.quark.cn/s/")) {
            return "QUARK";
        }
        if (lower.contains("pan.baidu.com/")) {
            return "BAIDU";
        }
        if (lower.contains("aliyundrive.com/") || lower.contains("alipan.com/")) {
            return "ALIYUN";
        }
        if (lower.contains("drive.uc.cn/")) {
            return "UC";
        }
        if (lower.contains("pan.xunlei.com/")) {
            return "XUNLEI";
        }
        if (lower.contains("115.com/")) {
            return "115";
        }
        if (lower.contains("123pan.com/") || lower.contains("123684.com/")) {
            return "123PAN";
        }
        if (lower.contains("mypikpak.com/")) {
            return "PIKPAK";
        }
        if (lower.contains("cloud.189.cn/")) {
            return "TIANYI";
        }
        if (lower.contains("yun.139.com/") || lower.contains("caiyun.139.com/")) {
            return "MOBILE";
        }
        return null;
    }

    private static String firstText(String... values) {
        if (values != null) {
            for (String value : values) {
                if (hasText(value)) {
                    return value.trim();
                }
            }
        }
        return null;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
