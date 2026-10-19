package com.gying.movie.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.gying.movie.entity.ResourceLink;
import com.gying.movie.utils.GyingTorrentLinks;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ResourceLinkVisibilityTest {
    @ParameterizedTest
    @ValueSource(strings = {
            "https://www.xn--wcv59z.com/dbt/naWV",
            "https://mirror.invalid/dbt/BT1/ticket",
            "https://mirror.invalid/DBT/BT1?ticket=rotated",
            "https://mirror.invalid/%64bt/BT1",
            "https://mirror.invalid/a/../dbt/BT1"})
    void accountDownloadRouteCoversMirrorsAndTicketForms(String url) {
        assertTrue(GyingTorrentLinks.isAccountDownload(url));
    }

    @Test void publicDetailHidesHistoricalDownloadRowsWithoutMutatingThem() {
        var service = spy(new ResourceLinkServiceImpl());
        ResourceLink old = link(1, "TORRENT", "P2P", "https://www.xn--wcv59z.com/dbt/naWV");
        ResourceLink magnet = link(2, "MAGNET", "P2P", "magnet:?xt=urn:btih:" + "a".repeat(40));
        ResourceLink quark = link(3, "TORRENT", "QUARK", "https://pan.quark.cn/s/fixture");
        ResourceLink xunlei = link(4, "TORRENT", "XUNLEI", "https://pan.xunlei.com/s/fixture");
        ResourceLink publicFile = link(5, "TORRENT", "P2P", "https://files.invalid/movie.torrent");
        doReturn(List.of(old, magnet, quark, xunlei, publicFile)).when(service).list(any(Wrapper.class));
        assertEquals(List.of(magnet, quark, xunlei, publicFile), service.getResourcesByMovieId("movie1"));
        assertEquals("ACTIVE", old.getStatus()); assertNull(old.getDeletedAt());
        verify(service, never()).updateById(any()); verify(service, never()).save(any());
    }

    private ResourceLink link(long id, String type, String provider, String url) {
        ResourceLink link = new ResourceLink(); link.setId(id); link.setType(type);
        link.setProvider(provider); link.setUrl(url); link.setStatus("ACTIVE"); return link;
    }
}
