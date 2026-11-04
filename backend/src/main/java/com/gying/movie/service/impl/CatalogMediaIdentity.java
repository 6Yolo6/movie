package com.gying.movie.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Catalog support for media identity, distinct from the catalog's broad category. */
final class CatalogMediaIdentity {
    private CatalogMediaIdentity() { }
    private static final String NON_MOVIE_KINDS="电视特辑(?:节目)?|电视特别节目|电视节目|电视纪录片|电视剧|剧集|音乐录影带|音乐录像带|电视音乐会|TV special|television special|television programme|television program|TV series|television series|music video";
    private static final String TYPE_MODIFIERS="(?:(?!讲述|描写|关于|记录|聚焦|围绕|制作|about)[^。\\n.!?，,:：]){0,24}";
    private static final Pattern NON_MOVIE = Pattern.compile(
        "(?is)^(?:(?:本节目|该作品|本片|该片|这部作品)(?:是|为)(?:一部|一档|一场)?"+TYPE_MODIFIERS+"|"
        + "这是(?:一部|一档|一场)?"+TYPE_MODIFIERS+"|这部|这档|该|This is (?:(?:a|an) )?"+TYPE_MODIFIERS+"|A |An )?"
        + "(?<kind>"+NON_MOVIE_KINDS+")(?:[\\s，,。.!?：:]|$|节目|讲述|记录|聚焦).*"
    );
    private static final Pattern NAMED_NON_MOVIE = Pattern.compile(
        "(?is)^(.{1,120}?)(?:是(?:一部|一档|一场)?|为(?:一部|一档|一场)?|is (?:(?:a|an) )?)"+TYPE_MODIFIERS
        + "(?<kind>"+NON_MOVIE_KINDS+")(?:[\\s，,。.!?：:]|$|节目|讲述|记录|聚焦).*"
    );
    private static final Pattern FILM_SELF = Pattern.compile(
        "(?is)(?:^|[，,。:：]\\s*)(?:本片|该片|这部影片|这部电影|影片|电影)(?:是|为|讲述|记录|描写|聚焦|呈现|以|围绕)"
        + "|(?:^|[，,。:：]\\s*)(?:这是一部|是一部|为一部|本片是)[^。\\n.!?]{0,24}(?:电影|影片|长片)"
        + "|^(?:一部|A |An )[^。\\n.!?]{0,24}(?:电影|影片|长片|film|feature)(?:[\\s，,。.!?：:]|$)"
    );
    private static final Pattern RECORDING = Pattern.compile(
        "(?is)(?:现场录像|现场录影|现场录制|录音棚.{0,12}(?:录像|录影)|演唱会录像|音乐会录像|"
        + "live recording|studio recording|concert video|performance video)"
    );
    static ObjectNode describe(JsonNode doc, ObjectMapper mapper) {
        String category=doc.path("category").asText("").toLowerCase(Locale.ROOT);
        String tmdb=doc.path("tmdbType").asText("").toLowerCase(Locale.ROOT);
        String summary=doc.path("summary").asText("");
        String prefix=summary.substring(0,Math.min(240,summary.length()));
        int introOffset=0;while(introOffset<prefix.length()&&prefix.charAt(introOffset)<=32)introOffset++;
        String intro=prefix.trim();
        Matcher nonMovie=NON_MOVIE.matcher(intro),self=FILM_SELF.matcher(intro),recording=RECORDING.matcher(intro);
        boolean explicitNonMovie=nonMovie.find(),explicitFilm=self.find(),recorded=recording.find();
        if(!explicitNonMovie){
            Matcher named=NAMED_NON_MOVIE.matcher(intro);
            if(named.find()&&namedSubjectMatches(doc,named.group(1))){nonMovie=named;explicitNonMovie=true;}
        }
        boolean tv=category.equals("tv")||tmdb.equals("tv");
        boolean movieSource=tmdb.equals("movie");
        ObjectNode result=mapper.createObjectNode(); ArrayNode evidence=result.putArray("evidence");
        if(explicitNonMovie) add(evidence,"summary",excerpt(summary,introOffset+nonMovie.start("kind"),introOffset+nonMovie.end("kind")));
        if(explicitFilm) add(evidence,"summary",excerpt(summary,introOffset+self.start(),introOffset+self.end()));
        if(!tmdb.isBlank()) add(evidence,"tmdbType",tmdb);
        if(tv&&!tmdb.equals("tv")) add(evidence,"category",category);
        // Imported season values may describe historical grouping rather than media type.
        if(doc.path("season").asInt(0)>0) add(evidence,"season",doc.path("season").asText());
        String status,kind;
        if(explicitNonMovie||tv) {
            status=(movieSource||explicitFilm||category.equals("mv"))?"conflict":"contradicted";kind="nonMovie";
        } else if(explicitFilm||movieSource&&!recorded) {
            status="supported";kind="movie";
        } else {
            status="uncertain";kind="unknown";
            if(recorded) add(evidence,"summary",excerpt(summary,introOffset+recording.start(),introOffset+recording.end()));
            if(evidence.isEmpty()&&!category.isBlank()) add(evidence,"category",category);
        }
        return result.put("status",status).put("kind",kind);
    }
    private static boolean namedSubjectMatches(JsonNode doc,String subject){
        String wanted=CatalogResearchService.key(subject);
        return !wanted.isBlank()&&(wanted.equals(CatalogResearchService.key(doc.path("titleCn").asText()))||wanted.equals(CatalogResearchService.key(doc.path("titleEn").asText())));
    }
    private static String excerpt(String summary,int start,int end){
        int from=Math.max(0,start-32),to=Math.min(summary.length(),Math.max(end+64,from+96));
        return summary.substring(from,Math.min(to,from+160));
    }
    private static void add(ArrayNode evidence,String source,String quote) {
        if(evidence.size()<3)evidence.addObject().put("source",source).put("quote",quote.substring(0,Math.min(160,quote.length())));
    }
    static boolean contradictsMovie(JsonNode doc,ObjectMapper mapper) {
        JsonNode identity=doc.path("mediaIdentity");
        String status=(identity.isObject()?identity:describe(doc,mapper)).path("status").asText();
        return status.equals("contradicted")||status.equals("conflict");
    }
}
