package com.gying.movie.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.gying.movie.config.ResourceHubProperties;
import com.gying.movie.entity.MovieMetadata;
import com.gying.movie.entity.ResourceDiscoveryResult;
import com.gying.movie.entity.ResourceLink;
import com.gying.movie.service.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CloudDrivePublishingPolicyTest {
    @ParameterizedTest @ValueSource(strings = {"SHARE_DISABLED", "TRANSFER_DISABLED"})
    void neverPublishesQuarkOriginalUrlWhenCreationIsDisabled(String policy) {
        var fixture = new Fixture("QUARK");
        if ("SHARE_DISABLED".equals(policy)) fixture.properties.getQuark().setShareEnabled(false);
        else fixture.properties.getQuark().setTransferEnabled(false);
        var result = fixture.service.publishDiscovery(11L);
        assertEquals(0, result.getPublished()); assertEquals(0, result.getUpdated());
        assertEquals(1, result.getSkipped()); assertEquals(0, result.getFailed());
        verifyNoInteractions(fixture.transfers, fixture.shares, fixture.resources);
        assertNull(fixture.discovery.getResourceLinkId());
    }

    @ParameterizedTest @ValueSource(strings = {"BAIDU", "UNSUPPORTED"})
    void unsupportedProviderCannotPublishEvenWhenAnOwnShareFieldExists(String provider) {
        var fixture = new Fixture(provider);
        fixture.discovery.setShareUrl("https://pan.baidu.com/s/own");
        var result = fixture.service.publishDiscovery(11L);
        assertEquals(1, result.getSkipped()); assertEquals(0, result.getPublished());
        verifyNoInteractions(fixture.transfers, fixture.shares, fixture.resources);
    }

    @ParameterizedTest @ValueSource(strings = {"QUARK", "XUNLEI"})
    void existingOwnShareCanBeStoredWhenFurtherTransfersAndSharesAreDisabled(String provider) {
        var fixture = new Fixture(provider);
        fixture.properties.getQuark().setTransferEnabled(false); fixture.properties.getQuark().setShareEnabled(false);
        fixture.properties.getXunlei().setEnabled(false); fixture.properties.getXunlei().setShareEnabled(false);
        fixture.discovery.setShareUrl("QUARK".equals(provider) ? "https://pan.quark.cn/s/own" : "https://pan.xunlei.com/s/own");
        when(fixture.resources.save(any(ResourceLink.class))).thenAnswer(call -> {
            ((ResourceLink) call.getArgument(0)).setId(42L); return true;
        });
        var result = fixture.service.publishDiscovery(11L);
        assertEquals(1, result.getPublished()); assertEquals(0, result.getSkipped()); assertEquals(0, result.getFailed());
        var saved = org.mockito.ArgumentCaptor.forClass(ResourceLink.class);
        verify(fixture.resources).save(saved.capture());
        assertEquals(fixture.discovery.getShareUrl(), saved.getValue().getUrl());
        assertNotEquals(fixture.discovery.getOriginalUrl(), saved.getValue().getUrl());
        verifyNoInteractions(fixture.transfers, fixture.shares);
    }

    private static class Fixture {
        final ResourceHubProperties properties = new ResourceHubProperties();
        final IResourceDiscoveryResultService discoveries = mock(IResourceDiscoveryResultService.class);
        final IResourceLinkService resources = mock(IResourceLinkService.class);
        final IQuarkTransferTaskService transfers = mock(IQuarkTransferTaskService.class);
        final IQuarkShareService shares = mock(IQuarkShareService.class);
        final IMovieMetadataService movies = mock(IMovieMetadataService.class);
        final ResourceDiscoveryResult discovery = new ResourceDiscoveryResult();
        final ResourceHubPublishServiceImpl service;
        Fixture(String provider) {
            properties.setEnabled(true);
            discovery.setId(11L); discovery.setMovieId("movie"); discovery.setTitle("测试电影 2026");
            discovery.setProvider(provider); discovery.setResourceType("DISK"); discovery.setStatus("DISCOVERED");
            discovery.setOriginalUrl("https://example.invalid/source"); discovery.setOriginalUrlHash("source-hash");
            var movie = new MovieMetadata(); movie.setId("movie"); movie.setTitleCn("测试电影"); movie.setYear(2026); movie.setStatus("ACTIVE");
            when(discoveries.getById(11L)).thenReturn(discovery); when(movies.getById("movie")).thenReturn(movie);
            service = new ResourceHubPublishServiceImpl(properties, discoveries, resources, transfers, shares, movies);
        }
    }
}
