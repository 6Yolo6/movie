package com.gying.movie.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gying.movie.dto.AdminMovieRequest;
import com.gying.movie.entity.MovieMetadata;
import com.gying.movie.service.IMovieMetadataService;
import com.gying.movie.service.impl.PosterStorageService;
import com.gying.movie.utils.AuthHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AdminMovieControllerMutationTest {
    private final IMovieMetadataService movies=mock(IMovieMetadataService.class);
    private final PosterStorageService posters=mock(PosterStorageService.class);
    private final AuthHelper auth=mock(AuthHelper.class);
    private final AdminMovieController controller=new AdminMovieController(movies,posters,auth,new ObjectMapper(),"https://images.example.test/movies");
    private final MovieMetadata canonical=new MovieMetadata();
    private final MovieMetadata before=new MovieMetadata();

    @BeforeEach void canonicalAlias() {
        canonical.setId("KeepCase"); canonical.setTitleCn("Retained title"); canonical.setStatus("ACTIVE");
        canonical.setResourceStatus("AVAILABLE"); canonical.setPopularity(17); canonical.setPosterUrl("retained.jpg");
        BeanUtils.copyProperties(canonical,before);
        when(movies.getById("OldCase")).thenReturn(canonical);
    }
    @Test void oldSourceUpdateCannotEditTheRetainedMovie() {
        var request=new AdminMovieRequest(); request.setTitleCn("Replacement title"); request.setStatus("DELETED");
        assertRejected(controller.update("OldCase",request,"Bearer fixture"));
    }
    @Test void oldSourceDeleteCannotDeleteTheRetainedMovie() {
        assertRejected(controller.delete("OldCase","Bearer fixture"));
    }
    @Test void oldSourcePosterCannotStoreABlobOrEditTheRetainedMovie() {
        var file=new MockMultipartFile("file","replacement.png","image/png",new byte[]{1,2,3});
        assertRejected(controller.uploadPoster("OldCase",file,"Bearer fixture"));
    }
    @Test void oldSourceReadStillReturnsTheRetainedMovie() {
        var result=controller.get("OldCase","Bearer fixture");
        assertEquals(200,result.getStatusCode().value()); assertSame(canonical,result.getBody());
        assertEquals("KeepCase",canonical.getId()); assertEquals("ACTIVE",canonical.getStatus());
        assertEquals("https://images.example.test/movies/retained.jpg",canonical.getPosterUrl());
        verify(movies).getById("OldCase"); verifyNoMoreInteractions(movies); verifyNoInteractions(posters);
    }
    private void assertRejected(ResponseEntity<?> response) {
        assertEquals(409,response.getStatusCode().value()); assertTrue(String.valueOf(response.getBody()).contains("KeepCase"));
        assertEquals(before,canonical); verify(movies).getById("OldCase"); verifyNoMoreInteractions(movies);
        verifyNoInteractions(posters);
    }
}
