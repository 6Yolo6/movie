package com.gying.movie.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class CatalogMediaIdentityTest {
    private final ObjectMapper mapper=new ObjectMapper();
    private ObjectNode movie(String summary){return mapper.createObjectNode().put("category","mv").put("summary",summary);}
    private ObjectNode identity(ObjectNode doc){return CatalogMediaIdentity.describe(doc,mapper);}
    @Test void broadMovieCategoryAloneCannotEstablishFilmIdentity(){
        var result=identity(movie("钢琴家在舞台上表演自己的曲目。"));
        assertThat(result.path("status").asText()).isEqualTo("uncertain");assertThat(result.path("kind").asText()).isEqualTo("unknown");
        assertThat(result.path("evidence").get(0).path("source").asText()).isEqualTo("category");
    }
    @Test void sourceMovieTypeSuppliesCatalogSupportWithoutInventingAnExternalSource(){
        var result=identity(movie("一家人跨越海洋寻找亲人。").put("tmdbType","movie"));
        assertThat(result.path("status").asText()).isEqualTo("supported");assertThat(result.path("kind").asText()).isEqualTo("movie");
        assertThat(result.path("evidence").get(0).path("source").asText()).isEqualTo("tmdbType");
        assertThat(result.path("evidence").get(0).path("quote").asText()).isEqualTo("movie");
    }
    @Test void explicitTvSpecialOverridesEvenAMovieSourceType(){
        var result=identity(movie("电视特辑节目\n歌手在音乐厅演唱新专辑。").put("tmdbType","movie"));
        assertThat(result.path("status").asText()).isEqualTo("conflict");assertThat(result.path("kind").asText()).isEqualTo("nonMovie");
        assertThat(result.path("evidence").get(0).path("quote").asText()).startsWith("电视特辑节目");
    }
    @Test void explicitTvTypeIsAConflictButASeasonAloneDoesNotEstablishType(){
        assertThat(identity(movie("一家人的故事。").put("tmdbType","tv")).path("status").asText()).isEqualTo("conflict");
        var uncertain=identity(movie("一家人的故事。").put("season",2));
        assertThat(uncertain.path("status").asText()).isEqualTo("uncertain");assertThat(uncertain.path("kind").asText()).isEqualTo("unknown");
        assertThat(uncertain.path("evidence").toString()).contains("season","2");
    }
    @Test void aRecordedPerformanceDoesNotBecomeAFilmFromTheMovieEndpointAlone(){
        var result=identity(movie("这是乐队在地下室录音棚里的现场录像，没有观众。").put("tmdbType","movie"));
        assertThat(result.path("status").asText()).isEqualTo("uncertain");
        assertThat(result.path("evidence").toString()).contains("现场录像");
    }
    @Test void filmSelfDescriptionCanSupportAConcertFilm(){
        var result=identity(movie("作为音乐家给世界的礼物，影片记录了最后一场钢琴独奏音乐会。"));
        assertThat(result.path("status").asText()).isEqualTo("supported");
        assertThat(result.path("evidence").get(0).path("source").asText()).isEqualTo("summary");
    }
    @Test void filmsAboutTelevisionOrFilmmakingAreNotClassifiedFromTheTopic(){
        assertThat(identity(movie("影片讲述一位电视特辑节目制作人的生活。")).path("status").asText()).isEqualTo("supported");
        assertThat(identity(movie("一位导演拍摄的电影讲述了他的童年。")).path("status").asText()).isEqualTo("uncertain");
    }
    @Test void aTvMovieIsNotRejectedMerelyForItsDistributionChannel(){
        assertThat(identity(movie("本片是一部电视电影，讲述母女之间的故事。")).path("status").asText()).isEqualTo("supported");
    }
    @Test void englishIdentityLabelsHaveTheSameConservativeBoundary(){
        assertThat(identity(movie("TV special. A band performs in a studio.")).path("status").asText()).isEqualTo("conflict");
        assertThat(identity(movie("A documentary film about a musician.")).path("status").asText()).isEqualTo("supported");
        assertThat(identity(movie("A live recording of a studio performance.")).path("status").asText()).isEqualTo("uncertain");
    }
    @Test void identityEvidenceIsBoundedAndDoesNotIncludeUnrelatedMetadata(){
        var doc=movie("影片记录了音乐家的一生。"+"演出资料。".repeat(300)).put("privateToken","fixture-only");
        var result=identity(doc);assertThat(result.path("evidence").size()).isLessThanOrEqualTo(3);
        result.path("evidence").forEach(e->assertThat(e.path("quote").asText().length()).isLessThanOrEqualTo(160));
        assertThat(result.toString()).doesNotContain("privateToken","fixture-only");
    }
    @Test void namedAndModifiedSelfDescriptionsAreConflictsButStoryCharactersAreNot(){
        assertThat(identity(movie("本片是一部英国电视特辑节目，歌手在音乐厅表演。")).path("status").asText()).isEqualTo("conflict");
        assertThat(identity(movie("《演唱样本》是一部电视特辑节目。").put("titleCn","演唱样本")).path("status").asText()).isEqualTo("conflict");
        assertThat(identity(movie("本片是一部讲述电视节目制作人的电影。")).path("status").asText()).isEqualTo("supported");
        assertThat(identity(movie("一位音乐家参加的是电视特辑节目。").put("titleCn","演唱样本")).path("status").asText()).isEqualTo("uncertain");
    }
    @Test void aLateSupportPhraseIsPresentInTheActualEvidenceExcerpt(){
        String summary="背景描述。".repeat(34)+"影片记录了音乐家的最后一次表演。";
        var result=identity(movie(summary));assertThat(result.path("status").asText()).isEqualTo("supported");
        assertThat(result.path("evidence").get(0).path("quote").asText()).contains("影片记录");
        assertThat(summary).contains(result.path("evidence").get(0).path("quote").asText());
    }
    @Test void leadingWhitespaceDoesNotMoveIdentityEvidenceAwayFromItsSourcePhrase(){
        for(String description:java.util.List.of("影片记录了最后一次音乐会。", "电视特辑节目，歌手现场演唱。", "这是录音棚里的现场录像。")){
            String summary=" ".repeat(170)+description;var result=identity(movie(summary));
            assertThat(result.path("status").asText()).isEqualTo(description.startsWith("影片")?"supported":description.startsWith("电视")?"conflict":"uncertain");
            assertThat(result.path("evidence").get(0).path("quote").asText()).contains(description);
            assertThat(summary).contains(result.path("evidence").get(0).path("quote").asText());
        }
    }
    @Test void legacySeasonValuesCannotOverrideExplicitMovieSupportOrDetermineAnimeType(){
        assertThat(identity(movie("一家人的故事。").put("tmdbType","movie").put("season",1)).path("status").asText()).isEqualTo("supported");
        assertThat(identity(movie("影片讲述一家人的旅行。").put("season",2)).path("status").asText()).isEqualTo("supported");
        var anime=identity(movie("少年踏上旅途。").put("category","ac").put("season",1));
        assertThat(anime.path("status").asText()).isEqualTo("uncertain");assertThat(anime.path("kind").asText()).isEqualTo("unknown");
        assertThat(identity(movie("一家人的故事。").put("tmdbType","tv").put("season",1)).path("status").asText()).isEqualTo("conflict");
    }
}
