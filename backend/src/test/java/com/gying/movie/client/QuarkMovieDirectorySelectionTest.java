package com.gying.movie.client;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gying.movie.config.ResourceHubProperties;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

class QuarkMovieDirectorySelectionTest {
    private static final String URL = "https://pan.quark.cn/s/fixture";
    private static final String TITLE = "穿普拉达的女王2";
    private static final String ENGLISH = " The Devil Wears Prada 2";
    private static final String DIRECTORY = "【普.拉.DA.2 (2026)】4K.UHD蓝光原盘REMUX 杜比视界 内封精修简英双语&简中特效sup字幕 年糕独家封装P8";
    private static final String VIDEO = "2026.2160p.UHD.BluRay.REMUX.HEVC.DV.HDR.TrueHD.7.1.Atmos-年糕.mkv";
    private static final String SOURCE = "🅰️【穿普拉达的女王2 (2026)】【4K.UHD蓝光原盘REMUX】";

    private record Fixture(QuarkAutoSaveClient client, QuarkMovieDirectoryAiClient ai, Map<String,String> tree) { }
    private String folder(String fid, String name) throws Exception {
        return new ObjectMapper().createObjectNode().put("fid",fid).put("file_name",name).put("dir",true).toString();
    }
    private String video(String name) throws Exception {
        return new ObjectMapper().createObjectNode().put("fid","video").put("file_name",name).put("dir",false).put("size",46357645187L).toString();
    }
    private Fixture fixture(String directory, String filename) throws Exception {
        return fixture(Map.of("root","["+folder("second",directory)+"]", "second","["+video(filename)+"]"));
    }
    private Fixture fixture(Map<String,String> input) {
        var tree = new HashMap<>(input);
        var properties = new ResourceHubProperties(); properties.getQuark().setToken("fixture-token");
        var rest = mock(RestTemplate.class); var builder = mock(RestTemplateBuilder.class);
        when(builder.build()).thenReturn(rest);
        when(rest.postForEntity(contains("/get_share_detail"),any(HttpEntity.class),eq(String.class))).thenAnswer(call -> {
            HttpEntity<?> request=call.getArgument(1);
            String url=String.valueOf(((Map<?,?>)request.getBody()).get("shareurl"));
            String key=url.contains("#/list/share/")?url.substring(url.lastIndexOf('/')+1):"root";
            assertTrue(tree.containsKey(key),"Out of observed scope: "+key);
            return ResponseEntity.ok("{\"success\":true,\"data\":{\"list\":"+tree.get(key)+"}}");
        });
        var ai=mock(QuarkMovieDirectoryAiClient.class);
        when(ai.select(any(),any(),any(),any(),anyInt(),any())).thenReturn("d0");
        return new Fixture(new QuarkAutoSaveClient(builder,new ObjectMapper(),properties,ai),ai,tree);
    }
    private QuarkAutoSaveClient.MovieShareSelection resolve(Fixture fixture) {
        return fixture.client().resolveMovieShareUrl(URL,TITLE,ENGLISH,null,2026,SOURCE);
    }
    private void rejectedWithoutModel(Fixture fixture) {
        assertThrows(IllegalStateException.class,()->resolve(fixture));
        verifyNoInteractions(fixture.ai());
    }

    @Test void recognizesTheActualSingleShareWithModelAndKeepsTheLeafScope() throws Exception {
        var f=fixture(DIRECTORY,VIDEO);
        var selected=resolve(f);
        assertEquals(URL+"#/list/share/second",selected.shareUrl()); assertTrue(selected.recursive());
        verify(f.ai()).select(eq(TITLE),eq(ENGLISH),isNull(),eq(2026),eq(2),argThat(c ->
                c.size()==1 && c.get(0).id().equals("d0") && c.get(0).path().contains("普.拉.DA.2")
                && !c.get(0).path().contains("https://") && c.get(0).videoNames().equals(List.of(VIDEO))));
    }
    @Test void recognizesTheActualCollectionButNeverSavesThe2006FirstFilm() throws Exception {
        var f=fixture(Map.of("root","["+folder("collection","【穿.普.拉.达.的.女.王 全2部】4K REMUX")+"]",
                "collection","["+folder("second",DIRECTORY)+","+folder("first","【第一部 (2006)】REMUX")+"]",
                "second","["+video(VIDEO)+"]","first","["+video("2006.BluRay.REMUX.1080p.AVC.DTS-HD.MA5.1-年糕.mkv")+"]"));
        var selected=f.client().resolveMovieShareUrl(URL,TITLE,ENGLISH,null,2026,"穿普拉达的女王 全2部");
        assertEquals(URL+"#/list/share/second",selected.shareUrl()); assertTrue(selected.recursive());
        verify(f.ai()).select(any(),any(),any(),any(),anyInt(),argThat(c->c.size()==1 && !c.get(0).path().contains("第一部")));
    }
    @Test void refusesAnAbbreviationWithoutVerifiedSourceOrCollectionContext() throws Exception {
        var f=fixture(DIRECTORY,VIDEO);
        assertThrows(IllegalStateException.class,()->f.client().resolveMovieShareUrl(URL,TITLE,ENGLISH,null,2026,"电影资源 2026"));
        verifyNoInteractions(f.ai());
    }
    @Test void neverInvokesModelForADeterministicFullTitleMatch() throws Exception {
        var f=fixture("穿普拉达的女王2 (2026)",VIDEO);
        assertEquals(URL+"#/list/share/second",resolve(f).shareUrl()); verifyNoInteractions(f.ai());
    }
    @Test void rejectsWrongYear() throws Exception { rejectedWithoutModel(fixture(DIRECTORY.replace("2026","2006"),VIDEO)); }
    @Test void rejectsTheFirstInstallmentWithTheSameYear() throws Exception { rejectedWithoutModel(fixture(DIRECTORY.replace("DA.2","DA.1"),VIDEO)); }
    @Test void rejectsPureInitials() throws Exception { rejectedWithoutModel(fixture("【P.L.D.2 (2026)】4K",VIDEO)); }
    @Test void rejectsAChineseCoreShorterThanThreeCharacters() throws Exception { rejectedWithoutModel(fixture("【普.拉.2 (2026)】4K",VIDEO)); }
    @Test void rejectsAnUnrelatedAbbreviatedTitle() throws Exception { rejectedWithoutModel(fixture("【复.仇.ZHE.2 (2026)】4K",VIDEO)); }
    @Test void rejectsNonconsecutiveChineseCharacters() throws Exception { rejectedWithoutModel(fixture("【普.女.WANG.2 (2026)】4K",VIDEO)); }
    @Test void rejectsAnotherFilmEvenIfItsFileContainsTheSameYear() throws Exception { rejectedWithoutModel(fixture(DIRECTORY,"Other.Title.2026.mkv")); }
    @Test void rejectsAnotherFilmAfterAYearPrefix() throws Exception { rejectedWithoutModel(fixture(DIRECTORY,"2026.Other.Title.mkv")); }
    @Test void rejectsAConflictingVideoYear() throws Exception { rejectedWithoutModel(fixture(DIRECTORY,VIDEO.replace("2026","2006"))); }
    @Test void rejectsACollectionAsTheSelectedDirectory() throws Exception { rejectedWithoutModel(fixture(DIRECTORY+" 全2部",VIDEO)); }
    @Test void rejectsUnverifiedNestedFolders() throws Exception {
        var f=fixture(DIRECTORY,VIDEO);
        f.tree().put("second","["+video(VIDEO)+","+folder("other","Extras")+"]"); f.tree().put("other","[]"); rejectedWithoutModel(f);
    }
    @Test void rejectsTheShareRootEvenWhenTheModelReturnsIt() throws Exception {
        var f=fixture(DIRECTORY,VIDEO);when(f.ai().select(any(),any(),any(),any(),anyInt(),any())).thenReturn("root");
        assertThrows(IllegalStateException.class,()->resolve(f));
    }
    @Test void rejectsAnUnknownModelCandidate() throws Exception {
        var f=fixture(DIRECTORY,VIDEO);when(f.ai().select(any(),any(),any(),any(),anyInt(),any())).thenReturn("d9");
        assertThrows(IllegalStateException.class,()->resolve(f));
    }
    @Test void keepsTheOriginalFailureWhenModelIsUnavailableOrUncertain() throws Exception {
        var f=fixture(DIRECTORY,VIDEO);when(f.ai().select(any(),any(),any(),any(),anyInt(),any())).thenReturn(null);
        assertTrue(assertThrows(IllegalStateException.class,()->resolve(f)).getMessage().contains("target movie directory"));
    }
    @Test void rejectsAFileTreeChangedDuringInference() throws Exception {
        var f=fixture(DIRECTORY,VIDEO);when(f.ai().select(any(),any(),any(),any(),anyInt(),any())).thenAnswer(call->{
            f.tree().put("second","["+video("2006.BluRay.REMUX.mkv")+"]");return "d0";});
        assertThrows(IllegalStateException.class,()->resolve(f));
    }
    @Test void rejectsADirectoryRenamedDuringInference() throws Exception {
        var f=fixture(DIRECTORY,VIDEO);when(f.ai().select(any(),any(),any(),any(),anyInt(),any())).thenAnswer(call->{
            f.tree().put("root","["+folder("second","Other Movie 2026")+"]");return "d0";});
        assertThrows(IllegalStateException.class,()->resolve(f));
    }
    @Test void neverWidensAnExplicitSourceFragmentForAbbreviatedContent() throws Exception {
        var f=fixture(DIRECTORY,VIDEO);
        assertThrows(IllegalStateException.class,()->f.client().resolveMovieShareUrl(URL+"#/list/share/second",TITLE,ENGLISH,null,2026,SOURCE));
        verifyNoInteractions(f.ai());
    }
    @Test void doesNotSendAnIncompleteTreeToTheModel() throws Exception {
        var f=fixture(DIRECTORY,VIDEO);
        f.tree().put("root","["+folder("second",DIRECTORY)+","+folder("deep0","Unknown collection")+"]");
        for(int i=0;i<7;i++)f.tree().put("deep"+i,"["+folder("deep"+(i+1),"Unknown")+"]");
        rejectedWithoutModel(f);
    }
    @Test void rejectsMixedVideosInTheCandidateLeaf() throws Exception {
        var f=fixture(DIRECTORY,VIDEO);f.tree().put("second","["+video(VIDEO)+","+video("Other.Title.2026.mkv")+"]");rejectedWithoutModel(f);
    }

    @Test void rejectsAnUnverifiedTitleDisguisedAsAnEncodingUploaderSuffix() throws Exception {
        rejectedWithoutModel(fixture(DIRECTORY,"2026.2160p-OtherTitle.mkv"));
    }

    @Test void rejectsAConflictingAncestorEvenWithAVerifiedFullSourceTitle() throws Exception {
        var f=fixture(Map.of(
                "root","["+folder("collection","【穿.普.拉.达.的.女.王 全2部】4K REMUX")+"]",
                "collection","["+folder("first","【第一部 (2006)】REMUX")+"]",
                "first","["+folder("second",DIRECTORY)+"]",
                "second","["+video(VIDEO)+"]"));
        rejectedWithoutModel(f);
    }

    @Test void rejectsACollectionContextRenamedDuringInference() throws Exception {
        var f=fixture(Map.of(
                "root","["+folder("collection","【穿.普.拉.达.的.女.王 全2部】4K REMUX")+"]",
                "collection","["+folder("second",DIRECTORY)+"]",
                "second","["+video(VIDEO)+"]"));
        when(f.ai().select(any(),any(),any(),any(),anyInt(),any())).thenAnswer(call->{
            f.tree().put("root","["+folder("collection","Other Collection (2026)")+"]");
            return "d0";
        });
        assertThrows(IllegalStateException.class,()->f.client().resolveMovieShareUrl(
                URL,TITLE,ENGLISH,null,2026,"电影资源 2026"));
        verify(f.ai()).select(any(),any(),any(),any(),anyInt(),any());
    }

    @Test void rejectsAnExplicitlyConflictingSourceYear() throws Exception {
        var f=fixture(DIRECTORY,VIDEO);
        assertThrows(IllegalStateException.class,()->f.client().resolveMovieShareUrl(
                URL,TITLE,ENGLISH,null,2026,TITLE+" (2006)"));
        verifyNoInteractions(f.ai());
    }

    @Test void refusesASeriesBaseAliasAsProofOfTheRequestedSequelSource() throws Exception {
        var f=fixture(DIRECTORY,VIDEO);
        assertThrows(IllegalStateException.class,()->f.client().resolveMovieShareUrl(
                URL,TITLE,ENGLISH,"穿普拉达的女王",2026,"穿普拉达的女王 (2026)"));
        verifyNoInteractions(f.ai());
    }
}
