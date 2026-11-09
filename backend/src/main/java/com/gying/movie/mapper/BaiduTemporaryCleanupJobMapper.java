package com.gying.movie.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gying.movie.entity.BaiduTemporaryCleanupJob;
import com.gying.movie.entity.BaiduTransferTask;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface BaiduTemporaryCleanupJobMapper extends BaseMapper<BaiduTemporaryCleanupJob> {
    @Select("SELECT * FROM baidu_temporary_cleanup_job WHERE transfer_task_id=#{taskId}")
    BaiduTemporaryCleanupJob findByTaskId(@Param("taskId") Long taskId);

    @Select("""
        SELECT t.* FROM baidu_transfer_task t LEFT JOIN baidu_temporary_cleanup_job j ON j.transfer_task_id=t.id
        WHERE j.id IS NULL AND t.scope_key LIKE 'QQ_BOT:%' AND t.phase='SHARED'
          AND t.share_confirmed_at IS NOT NULL AND t.cleanup_evidence IS NOT NULL
        ORDER BY t.share_confirmed_at,t.id LIMIT 100
        """)
    List<BaiduTransferTask> findConfirmedMissingCleanup();

    @Select("""
        SELECT * FROM baidu_temporary_cleanup_job
        WHERE status IN ('PENDING','TRASH_SUBMITTING','TRASH_UNKNOWN','TRASHED','PURGE_SUBMITTING','PURGE_UNKNOWN','REMOTE_DELETED')
          AND delete_after<=#{now} AND (lease_until IS NULL OR lease_until<#{now})
        ORDER BY delete_after,id LIMIT 20
        """)
    List<BaiduTemporaryCleanupJob> findDue(@Param("now") LocalDateTime now);

    @Update("""
        UPDATE baidu_temporary_cleanup_job SET lease_owner=#{owner},lease_until=#{until},updated_at=#{now},attempts=attempts+1
        WHERE id=#{id} AND status=#{status} AND delete_after<=#{now}
          AND status IN ('PENDING','TRASH_SUBMITTING','TRASH_UNKNOWN','TRASHED','PURGE_SUBMITTING','PURGE_UNKNOWN','REMOTE_DELETED')
          AND (lease_until IS NULL OR lease_until<#{now})
        """)
    int claim(@Param("id") Long id, @Param("status") String status, @Param("owner") String owner,
            @Param("now") LocalDateTime now, @Param("until") LocalDateTime until);

    @Update("""
        UPDATE baidu_temporary_cleanup_job SET status=#{next},last_error=#{error},
          recycle_evidence=COALESCE(#{recycleEvidence},recycle_evidence),updated_at=#{now},lease_until=#{until},
          finished_at=CASE WHEN #{next}='SUCCEEDED' THEN #{now} ELSE finished_at END
        WHERE id=#{id} AND lease_owner=#{owner} AND lease_until>#{now} AND status=#{expected}
          AND ((status='PENDING' AND #{next} IN ('PENDING','TRASH_SUBMITTING','REVIEW_REQUIRED'))
            OR (status IN ('TRASH_SUBMITTING','TRASH_UNKNOWN') AND #{next} IN ('TRASH_UNKNOWN','TRASHED','REVIEW_REQUIRED'))
            OR (status='TRASHED' AND #{next} IN ('TRASHED','PURGE_SUBMITTING','REVIEW_REQUIRED'))
            OR (status IN ('PURGE_SUBMITTING','PURGE_UNKNOWN') AND #{next} IN ('PURGE_UNKNOWN','REMOTE_DELETED','REVIEW_REQUIRED'))
            OR (status='REMOTE_DELETED' AND #{next} IN ('REMOTE_DELETED','SUCCEEDED')))
        """)
    int transition(@Param("id") Long id, @Param("owner") String owner, @Param("expected") String expected,
            @Param("next") String next, @Param("error") String error, @Param("recycleEvidence") String recycleEvidence,
            @Param("now") LocalDateTime now, @Param("until") LocalDateTime until);

    @Update("UPDATE baidu_temporary_cleanup_job SET lease_owner=NULL,lease_until=NULL WHERE id=#{id} AND lease_owner=#{owner}")
    int release(@Param("id") Long id, @Param("owner") String owner);

    @Update("""
        UPDATE baidu_temporary_cleanup_job SET resource_link_id=#{linkId},updated_at=#{now}
        WHERE transfer_task_id=#{taskId} AND discovery_result_id=#{discoveryId} AND BINARY share_url_hash=BINARY #{shareHash}
          AND (resource_link_id IS NULL OR resource_link_id=#{linkId})
        """)
    int bindResource(@Param("taskId") Long taskId, @Param("discoveryId") Long discoveryId,
            @Param("linkId") Long linkId, @Param("shareHash") String shareHash, @Param("now") LocalDateTime now);

    @Update("""
        UPDATE resource_link l INNER JOIN resource_discovery_result d ON d.id=#{job.discoveryResultId} AND d.resource_link_id=l.id
          INNER JOIN baidu_transfer_task t ON t.id=#{job.transferTaskId}
        SET l.status='DELETED',l.link_status='INVALID',l.deleted_at=#{now},l.validated_at=#{now},
          l.last_check_error='百度搜索临时资源已按计划彻底清理',l.updated_at=#{now}
        WHERE l.id=#{job.resourceLinkId} AND l.provider='BAIDU' AND l.source='BAIDU_TEMP'
          AND BINARY l.source_ref=BINARY #{job.scopeKey} AND BINARY l.url_hash=BINARY #{job.shareUrlHash}
          AND BINARY l.source_url=BINARY #{job.originalUrl} AND l.movie_id=#{job.movieId}
          AND d.provider='BAIDU' AND d.source='QQ_BOT' AND BINARY d.source_ref=BINARY CONCAT('BAIDU_TEMP:',#{job.scopeKey})
          AND BINARY d.share_url_hash=BINARY #{job.shareUrlHash} AND d.movie_id=#{job.movieId}
          AND BINARY d.original_url_hash=BINARY #{job.originalUrlHash} AND BINARY d.original_url=BINARY #{job.originalUrl}
          AND t.discovery_result_id=d.id AND BINARY t.scope_key=BINARY #{job.scopeKey}
          AND BINARY t.share_url_hash=BINARY #{job.shareUrlHash} AND BINARY t.original_url_hash=BINARY #{job.originalUrlHash}
        """)
    int invalidateLink(@Param("job") BaiduTemporaryCleanupJob job, @Param("now") LocalDateTime now);

    @Update("""
        UPDATE resource_discovery_result d INNER JOIN baidu_transfer_task t ON t.id=#{job.transferTaskId}
          LEFT JOIN resource_link l ON l.id=d.resource_link_id
        SET d.status='IGNORED',d.failure_reason='百度搜索临时资源已按计划彻底清理',d.updated_at=#{now}
        WHERE d.id=#{job.discoveryResultId} AND d.provider='BAIDU' AND d.source='QQ_BOT'
          AND BINARY d.source_ref=BINARY CONCAT('BAIDU_TEMP:',#{job.scopeKey})
          AND BINARY d.share_url_hash=BINARY #{job.shareUrlHash} AND d.movie_id=#{job.movieId}
          AND BINARY d.original_url_hash=BINARY #{job.originalUrlHash} AND BINARY d.original_url=BINARY #{job.originalUrl}
          AND t.discovery_result_id=d.id AND BINARY t.scope_key=BINARY #{job.scopeKey}
          AND BINARY t.share_url_hash=BINARY #{job.shareUrlHash} AND BINARY t.original_url_hash=BINARY #{job.originalUrlHash}
          AND ((#{job.resourceLinkId} IS NULL AND d.resource_link_id IS NULL) OR
            (d.resource_link_id=#{job.resourceLinkId} AND l.provider='BAIDU' AND l.source='BAIDU_TEMP'
             AND BINARY l.source_ref=BINARY #{job.scopeKey} AND BINARY l.url_hash=BINARY #{job.shareUrlHash}
             AND BINARY l.source_url=BINARY #{job.originalUrl} AND l.movie_id=#{job.movieId}))
        """)
    int invalidateDiscovery(@Param("job") BaiduTemporaryCleanupJob job, @Param("now") LocalDateTime now);
}
