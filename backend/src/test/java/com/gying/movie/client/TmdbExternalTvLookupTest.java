package com.gying.movie.client;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gying.movie.config.ResourceHubProperties;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

class TmdbExternalTvLookupTest {
    final RestTemplate rest=new RestTemplate();
    final MockRestServiceServer server=MockRestServiceServer.bindTo(rest).build();
    final ResourceHubProperties properties=new ResourceHubProperties();
    TmdbClient client(){
        properties.getTmdb().setApiKey("fixture-only");properties.getTmdb().setRequestIntervalMs(0);
        return new TmdbClient(rest,new ObjectMapper(),properties);
    }
    @Test void imdbLookupUsesExactTvResultsAndIgnoresMovieAndEpisodeIds() {
        server.expect(requestTo("https://api.themoviedb.org/3/find/tt20242042?api_key=fixture-only&external_source=imdb_id&language=zh-CN"))
                .andRespond(withSuccess("{\"tv_results\":[{\"id\":204541}],\"movie_results\":[{\"id\":999}],\"tv_episode_results\":[{\"id\":777}]}",MediaType.APPLICATION_JSON));
        assertEquals(List.of(204541L),client().findTvByExternalId("tt20242042","imdb_id"));server.verify();
    }
    @Test void tvdbLookupIsAnExactExternalIdQuery() {
        server.expect(requestTo("https://api.themoviedb.org/3/find/421555?api_key=fixture-only&external_source=tvdb_id&language=zh-CN"))
                .andRespond(withSuccess("{\"tv_results\":[]}",MediaType.APPLICATION_JSON));
        assertTrue(client().findTvByExternalId("421555","tvdb_id").isEmpty());server.verify();
    }
    @Test void missingTvResultsCannotBeTreatedAsAnExactMiss() {
        server.expect(requestTo("https://api.themoviedb.org/3/find/421555?api_key=fixture-only&external_source=tvdb_id&language=zh-CN"))
                .andRespond(withSuccess("{\"movie_results\":[]}",MediaType.APPLICATION_JSON));
        assertThrows(IllegalStateException.class,()->client().findTvByExternalId("421555","tvdb_id"));server.verify();
    }
    @Test void arbitrarySourceAndInvalidPathIdsAreRejectedWithoutRequests() {
        assertThrows(IllegalArgumentException.class,()->client().findTvByExternalId("421555/../other","tvdb_id"));
        assertThrows(IllegalArgumentException.class,()->client().findTvByExternalId("421555","unknown"));
        assertThrows(IllegalArgumentException.class,()->client().findTvByExternalId("wrong","imdb_id"));server.verify();
    }
}
