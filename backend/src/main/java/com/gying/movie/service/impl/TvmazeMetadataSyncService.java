package com.gying.movie.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gying.movie.client.TmdbClient;
import com.gying.movie.client.TvmazeClient;
import com.gying.movie.config.ResourceHubProperties;
import com.gying.movie.dto.TmdbSyncResult;
import com.gying.movie.entity.MovieMetadata;
import com.gying.movie.entity.MovieSourceIdentity;
import com.gying.movie.entity.ResourceHubTask;
import com.gying.movie.service.IMovieMetadataService;
import com.gying.movie.service.IMovieSourceIdentityService;
import com.gying.movie.service.IResourceHubTaskService;
import com.gying.movie.service.ITmdbMetadataSyncService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jsoup.Jsoup;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class TvmazeMetadataSyncService {
    private final ResourceHubProperties properties;
    private final IResourceHubTaskService tasks;
    private final IMovieMetadataService movies;
    private final IMovieSourceIdentityService identities;
    private final TvmazeClient tvmaze;
    private final TmdbClient tmdb;
    private final ITmdbMetadataSyncService tmdbSync;
    private final ObjectMapper mapper;
    private final TransactionTemplate transactions;
    private final TvmazeUpdatesCheckpoint checkpoint;
    private final Clock clock;

    @Autowired
    public TvmazeMetadataSyncService(ResourceHubProperties properties, IResourceHubTaskService tasks,
            IMovieMetadataService movies, IMovieSourceIdentityService identities, TvmazeClient tvmaze,
            TmdbClient tmdb, ITmdbMetadataSyncService tmdbSync, ObjectMapper mapper, PlatformTransactionManager transactionManager) {
        this(properties, tasks, movies, identities, tvmaze, tmdb, tmdbSync, mapper, transactionManager, Clock.systemUTC());
    }
    TvmazeMetadataSyncService(ResourceHubProperties properties, IResourceHubTaskService tasks,
            IMovieMetadataService movies, IMovieSourceIdentityService identities, TvmazeClient tvmaze,
            TmdbClient tmdb, ITmdbMetadataSyncService tmdbSync, ObjectMapper mapper, PlatformTransactionManager transactionManager, Clock clock) {
        this.properties = properties; this.tasks = tasks; this.movies = movies; this.identities = identities;
        this.tvmaze = tvmaze; this.tmdb = tmdb; this.tmdbSync = tmdbSync; this.mapper = mapper; this.clock = clock;
        this.transactions = new TransactionTemplate(transactionManager);
        this.checkpoint = new TvmazeUpdatesCheckpoint(tasks, mapper);
    }

    public synchronized ResourceHubTask enqueueAutomatic() {
        var config = properties.getTvmaze();
        if (!properties.isEnabled() || !config.isAutoSyncEnabled()) return null;
        if (tasks.count(checkpoint.query().in("status", "PENDING", "RUNNING")) > 0) return null;
        ResourceHubTask latest = checkpoint.latest();
        LocalDateTime cutoff = LocalDateTime.now(clock.withZone(java.time.ZoneId.systemDefault()))
                .minusHours(Math.max(config.getAutoSyncIntervalHours(), 24));
        if (latest != null && latest.getCreatedAt() != null && !latest.getCreatedAt().isBefore(cutoff)) return null;
        return checkpoint.enqueue(latest, config.getAutoSyncMaxItems());
    }

    public TmdbSyncResult runTask(Long id) {
        ResourceHubTask task = tasks.getById(id);
        if (task == null) throw new IllegalArgumentException("TVmaze task does not exist");
        ObjectNode payload = checkpoint.read(task), state = (ObjectNode) payload.path("crawl");
        TmdbSyncResult result = new TmdbSyncResult();
        result.setTaskId(id); result.setSource("TVMAZE_UPDATES"); result.setRequested(state.path("roundBudget").asInt());
        result.setStatus(task.getStatus());
        if (!properties.isEnabled() || !properties.getWorker().isEnabled() || !properties.getTvmaze().isAutoSyncEnabled()) return result;
        // Older checkpoints must not be replayed after a newer daily task has inherited their budget.
        ResourceHubTask latest = checkpoint.latest();
        if (latest != null && !Objects.equals(id, latest.getId())) return result;
        if (!checkpoint.start(task)) return result;
        try {
            long now = clock.instant().getEpochSecond();
            if (state.path("nextRequestAt").asLong() > now) {
                checkpoint.finish(task, "FAILED", "TVmaze Retry-After has not elapsed");
                result.setStatus(task.getStatus()); return result;
            }
            if (!state.path("scanDone").asBoolean()) {
                long lastScan = state.path("lastSuccessfulScanAt").asLong();
                String window = TvmazeUpdatesCheckpoint.window(lastScan, now);
                Map<Long, Long> feed = tvmaze.fetchUpdates(window);
                if (feed == null) throw new IllegalStateException("TVmaze update feed is missing");
                state.put("window", window);
                state.put("coverageGap", lastScan > 0 && now - lastScan > 30 * 86400L);
                result.setSkipped(checkpoint.merge(state, feed, knownVersions(), now));
                checkpoint.persist(task, payload);
            }
            if (state.path("budgetStartedAt").asLong() == 0 || now - state.path("budgetStartedAt").asLong() >= 86400) {
                state.put("budgetStartedAt", now); state.put("dailyAttempts", 0);
            }
            int budget = Math.min(state.path("roundBudget").asInt(), Math.min(Math.max(properties.getTvmaze().getAutoSyncMaxItems(), 1), 10));
            ObjectNode pending = (ObjectNode) state.path("pending"), deferred = (ObjectNode) state.path("deferred");
            for (String key : checkpoint.ordered(state)) {
                if (state.path("roundAttempts").asInt() >= budget || state.path("dailyAttempts").asInt() >= budget) break;
                if (!properties.getWorker().isEnabled() || !properties.getTvmaze().isAutoSyncEnabled()) break;
                ObjectNode item = (ObjectNode) pending.get(key);
                if (item.path("attempts").asInt() >= 3) continue;
                state.put("roundAttempts", state.path("roundAttempts").asInt() + 1);
                state.put("dailyAttempts", state.path("dailyAttempts").asInt() + 1);
                item.put("attempts", item.path("attempts").asInt() + 1);
                checkpoint.persist(task, payload);
                try {
                    long showId = Long.parseLong(key);
                    JsonNode show = tvmaze.fetchShow(showId);
                    if (show == null || show.path("id").asLong() != showId
                            || show.path("updated").asLong() < item.path("updated").asLong()) {
                        throw new IllegalStateException("TVmaze cached detail is older than the change feed");
                    }
                    MovieMetadata saved = transactions.execute(status -> collect(showId, show));
                    if (saved == null) {
                        ObjectNode candidate = item.deepCopy();
                        candidate.put("reason", "NO_EXACT_TMDB_ID"); candidate.put("title", show.path("name").asText());
                        candidate.put("premiered", show.path("premiered").asText(null));
                        candidate.set("externals", show.path("externals").deepCopy());
                        deferred.set(key, candidate); result.setSkipped(result.getSkipped() + 1);
                    } else {
                        result.setProcessed(result.getProcessed() + 1);
                        result.setUpdated(result.getUpdated() + 1);
                    }
                    pending.remove(key);
                } catch (TvmazeClient.RateLimited rateLimit) {
                    state.put("nextRequestAt", rateLimit.retryAt().getEpochSecond());
                    item.put("lastErrorCategory", "RATE_LIMITED"); result.setFailed(result.getFailed() + 1);
                    checkpoint.persist(task, payload); break;
                } catch (MetadataUpdatesCheckpoint.CheckpointConflict conflict) { throw conflict; }
                catch (Exception failure) {
                    item.put("lastErrorCategory", failure.getClass().getSimpleName());
                    result.setFailed(result.getFailed() + 1);
                }
                checkpoint.persist(task, payload);
            }
            ObjectNode summary = state.putObject("lastRound");
            summary.put("processed", result.getProcessed()); summary.put("failed", result.getFailed());
            summary.put("skipped", result.getSkipped());
            boolean exhausted = false;
            for (JsonNode item : state.path("pending")) if (item.path("attempts").asInt() >= 3) exhausted = true;
            checkpoint.persist(task, payload);
            checkpoint.finish(task, result.getFailed() > 0 || exhausted ? "FAILED" : "SUCCEEDED",
                    exhausted ? "TVmaze items require review after three failures" : result.getFailed() > 0 ? "TVmaze items retained for retry" : null);
        } catch (Exception failure) {
            if (failure instanceof TvmazeClient.RateLimited rateLimit) {
                state.put("nextRequestAt", rateLimit.retryAt().getEpochSecond());
                try { checkpoint.persist(task, payload); } catch (MetadataUpdatesCheckpoint.CheckpointConflict ignored) { }
            }
            if (!(failure instanceof MetadataUpdatesCheckpoint.CheckpointConflict)) {
                try { checkpoint.finish(task, "FAILED", "TVmaze updates failed: " + failure.getClass().getSimpleName()); }
                catch (MetadataUpdatesCheckpoint.CheckpointConflict ignored) { }
            }
            result.setFailed(Math.max(result.getFailed(), 1));
            result.getErrors().add(failure.getClass().getSimpleName());
        }
        result.setStatus("RUNNING".equals(task.getStatus()) ? "FAILED" : task.getStatus()); return result;
    }

    private Map<Long, Long> knownVersions() {
        Map<Long, Long> versions = new LinkedHashMap<>();
        for (MovieSourceIdentity identity : identities.list(new QueryWrapper<MovieSourceIdentity>()
                .eq("source", "TVMAZE").eq("source_type", "tv").eq("season", 1).eq("match_status", "CONFIRMED"))) {
            if (!identity.getExternalId().matches("[1-9][0-9]{0,11}")) throw new IllegalStateException("Invalid bound TVmaze ID");
            long id = Long.parseLong(identity.getExternalId());
            if (versions.containsKey(id)) throw new IllegalStateException("Multiple bound TVmaze canonical identities require review");
            versions.put(id, evidence(identity).path("upstreamUpdated").asLong());
        }
        return versions;
    }

    /** Only metadata/identity writes occur here. This provider has no cloud/resource side effects. */
    private MovieMetadata collect(long showId, JsonNode show) {
        MovieSourceIdentity identity = identities.getOne(new QueryWrapper<MovieSourceIdentity>()
                .eq("source", "TVMAZE").eq("source_type", "tv").eq("external_id", String.valueOf(showId))
                .eq("season", 1).last("LIMIT 1 FOR UPDATE"), false);
        JsonNode previous = evidence(identity);
        MovieMetadata movie;
        if (identity != null) {
            if (!"CONFIRMED".equals(identity.getMatchStatus()) || !"TVMAZE".equals(identity.getSource())
                    || !String.valueOf(showId).equals(identity.getExternalId()) || !"tv".equals(identity.getSourceType())
                    || !Objects.equals(1, identity.getSeason())) throw new IllegalStateException("TVmaze binding requires review");
            movie = movies.getById(identity.getMovieId());
            if (movie == null || !"ACTIVE".equals(movie.getStatus()) || movie.getDeletedAt() != null
                    || !List.of("tv", "ac").contains(movie.getCategory()) || movie.getTmdbType() != null && !"tv".equals(movie.getTmdbType())
                    || movie.getSeason() != null && movie.getSeason() != 1) {
                throw new IllegalStateException("TVmaze binding has no active series canonical record");
            }
            for (String name : List.of("imdb", "thetvdb")) {
                JsonNode prior = previous.path("externals").path(name), current = show.path("externals").path(name);
                if (!prior.isMissingNode() && !prior.isNull() && !current.isMissingNode() && !current.isNull() && !prior.equals(current))
                    throw new IllegalStateException("TVmaze external identity changed");
            }
        } else {
            Integer year = year(show.path("premiered").asText(null));
            if (year == null || properties.getTmdb().getApiKey() == null || properties.getTmdb().getApiKey().isBlank()) return null;
            Long tmdbId = null;
            for (String source : List.of("imdb", "thetvdb")) {
                String external = show.path("externals").path(source).asText(null);
                if (external == null || external.isBlank()) continue;
                List<Long> matches = tmdb.findTvByExternalId(external, source.equals("imdb") ? "imdb_id" : "tvdb_id");
                if (matches == null || matches.size() > 1) throw new IllegalStateException("External TV identity is ambiguous");
                if (matches.size() == 1) {
                    if (tmdbId != null && !tmdbId.equals(matches.get(0))) throw new IllegalStateException("IMDb and TVDB identities disagree");
                    tmdbId = matches.get(0);
                }
            }
            if (tmdbId == null) return null;
            movie = tmdbSync.syncExactTv(tmdbId, year);
            if (movie == null || movie.getId() == null || !"tv".equals(movie.getTmdbType())
                    || !tmdbId.equals(movie.getTmdbId()) || !"ACTIVE".equals(movie.getStatus()) || movie.getDeletedAt() != null
                    || !year.equals(movie.getYear()) || movie.getSeason() != null && movie.getSeason() != 1) {
                throw new IllegalStateException("Exact TV canonical metadata was not saved");
            }
            identity = new MovieSourceIdentity(); identity.setCreatedAt(LocalDateTime.now());
        }
        ObjectNode proof = mapper.createObjectNode();
        proof.put("tvmazeId", showId); proof.put("upstreamUpdated", show.path("updated").asLong());
        proof.put("sourceUrl", "https://www.tvmaze.com/shows/" + showId); proof.put("license", TvmazeClient.LICENSE);
        proof.put("changes", "Selected fields normalized; existing local metadata preserved");
        proof.set("externals", show.path("externals").deepCopy());
        proof.set("ownedFields", applyFallbackFields(movie, show, previous.path("ownedFields")));
        identity.setMovieId(movie.getId()); identity.setSource("TVMAZE"); identity.setSourceType("tv");
        identity.setExternalId(String.valueOf(showId)); identity.setSeason(1);
        identity.setConfidence(BigDecimal.valueOf(100)); identity.setMatchMethod("TVMAZE_EXTERNAL_ID");
        identity.setMatchStatus("CONFIRMED"); identity.setEvidenceJson(proof.toString()); identity.setUpdatedAt(LocalDateTime.now());
        if (!(identity.getId() == null ? identities.save(identity) : identities.updateById(identity)))
            throw new IllegalStateException("TVmaze identity/checkpoint was not saved");
        return movie;
    }

    private ObjectNode applyFallbackFields(MovieMetadata movie, JsonNode show, JsonNode owned) {
        Map<String, Object> proposed = new LinkedHashMap<>(), current = new LinkedHashMap<>();
        String summary = show.path("summary").asText("");
        proposed.put("summary", text(Jsoup.parse(summary).text(), 4000)); current.put("summary", movie.getSummary());
        proposed.put("titleEn", text(show.path("name").asText(""), 500)); current.put("titleEn", movie.getTitleEn());
        List<String> genres = new java.util.ArrayList<>();
        for (JsonNode genre : show.path("genres")) if (genre.isTextual() && !genre.asText().isBlank()) genres.add(text(genre.asText(), 100));
        proposed.put("genres", genres.stream().distinct().limit(12).toList()); current.put("genres", movie.getGenres());
        String language = text(show.path("language").asText(""), 100);
        proposed.put("languages", language.isBlank() ? List.of() : List.of(language)); current.put("languages", movie.getLanguages());
        String country = text(show.path("network").path("country").path("name").asText(
                show.path("webChannel").path("country").path("name").asText("")), 100);
        proposed.put("regions", country.isBlank() ? List.of() : List.of(country)); current.put("regions", movie.getRegions());
        long runtime = show.path("runtime").asLong(show.path("averageRuntime").asLong());
        proposed.put("runtime", runtime > 0 && runtime <= 1440 ? runtime + "分钟" : ""); current.put("runtime", movie.getRuntime());
        ObjectNode nextOwned = mapper.createObjectNode(); boolean changed = false;
        for (var entry : proposed.entrySet()) {
            String field = entry.getKey(); JsonNode value = mapper.valueToTree(entry.getValue()), before = mapper.valueToTree(current.get(field));
            boolean empty = before.isNull() || before.isArray() && before.isEmpty() || before.isTextual() && before.asText().isBlank();
            boolean wasOurs = owned.has(field) && owned.path(field).equals(before);
            if (!(empty || wasOurs)) continue;
            if (value.isTextual() && value.asText().isBlank() || value.isArray() && value.isEmpty()) {
                if (wasOurs) nextOwned.set(field, before);
                continue;
            }
            nextOwned.set(field, value);
            if (value.equals(before)) continue;
            changed = true;
            switch (field) {
                case "summary" -> movie.setSummary(value.asText());
                case "titleEn" -> movie.setTitleEn(value.asText());
                case "runtime" -> movie.setRuntime(value.asText());
                case "genres" -> movie.setGenres((List<String>) entry.getValue());
                case "languages" -> movie.setLanguages((List<String>) entry.getValue());
                case "regions" -> movie.setRegions((List<String>) entry.getValue());
                default -> throw new IllegalStateException("Unsupported TVmaze metadata field");
            }
        }
        if (changed) {
            movie.setUpdatedAt(LocalDateTime.now());
            if (!movies.updateById(movie)) throw new IllegalStateException("TVmaze metadata was not saved");
        }
        return nextOwned;
    }
    private JsonNode evidence(MovieSourceIdentity identity) {
        if (identity == null || identity.getEvidenceJson() == null) return mapper.createObjectNode();
        try {
            JsonNode value = mapper.readTree(identity.getEvidenceJson());
            if (value != null && value.isObject()) return value;
        } catch (Exception ignored) { }
        throw new IllegalStateException("Invalid TVmaze identity evidence");
    }
    private Integer year(String date) {
        try { return date == null ? null : LocalDate.parse(date).getYear(); }
        catch (RuntimeException ignored) { return null; }
    }
    private String text(String value, int max) { return value == null ? "" : value.trim().substring(0, Math.min(value.trim().length(), max)); }
}
