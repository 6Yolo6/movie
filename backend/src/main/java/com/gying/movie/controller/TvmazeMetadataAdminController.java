package com.gying.movie.controller;

import com.gying.movie.dto.ApiResponse;
import com.gying.movie.dto.TmdbSyncResult;
import com.gying.movie.entity.ResourceHubTask;
import com.gying.movie.service.IResourceHubConfigService;
import com.gying.movie.service.impl.TvmazeMetadataSyncService;
import com.gying.movie.utils.AuthHelper;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/resource-hub/tvmaze/metadata-sync")
public class TvmazeMetadataAdminController {
    private final AuthHelper auth;
    private final TvmazeMetadataSyncService sync;
    private final IResourceHubConfigService config;
    public TvmazeMetadataAdminController(AuthHelper auth, TvmazeMetadataSyncService sync, IResourceHubConfigService config) {
        this.auth = auth; this.sync = sync; this.config = config;
    }
    @PostMapping
    public ApiResponse<ResourceHubTask> checkUpdates(@RequestHeader(value = "Authorization", required = false) String token) {
        auth.requireAdmin(token); config.reload();
        return ApiResponse.ok(sync.enqueueAutomatic());
    }
    @PostMapping("/{taskId}/run")
    public ApiResponse<TmdbSyncResult> run(@PathVariable Long taskId,
            @RequestHeader(value = "Authorization", required = false) String token) {
        auth.requireAdmin(token); config.reload();
        return ApiResponse.ok(sync.runTask(taskId));
    }
}
