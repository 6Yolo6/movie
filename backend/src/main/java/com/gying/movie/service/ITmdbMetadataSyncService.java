package com.gying.movie.service;

import com.gying.movie.dto.MovieSearchCandidate;
import com.gying.movie.dto.ResourceHubMetadataSyncRequest;
import com.gying.movie.dto.TmdbSyncResult;
import com.gying.movie.entity.MovieMetadata;
import com.gying.movie.entity.ResourceHubTask;
import java.util.List;

public interface ITmdbMetadataSyncService {
    ResourceHubTask enqueue(ResourceHubMetadataSyncRequest request);

    TmdbSyncResult runTask(Long taskId);

    MovieMetadata syncBestByKeyword(String keyword);

    MovieMetadata syncExactByKeyword(String keyword);

    List<MovieSearchCandidate> searchCandidatesByKeyword(String keyword, int limit);
}
