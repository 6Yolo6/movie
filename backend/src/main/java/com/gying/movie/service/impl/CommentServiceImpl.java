package com.gying.movie.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.gying.movie.dto.CommentDisplayDTO;
import com.gying.movie.entity.Comment;
import com.gying.movie.entity.SysUser;
import com.gying.movie.mapper.CommentMapper;
import com.gying.movie.mapper.SysUserMapper;
import com.gying.movie.service.ICommentService;
import com.gying.movie.utils.Sanitizer;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class CommentServiceImpl extends ServiceImpl<CommentMapper, Comment> implements ICommentService {

    @Autowired
    private SysUserMapper sysUserMapper;

    @Autowired
    private Sanitizer sanitizer;

    @Override
    public boolean save(Comment comment) {
        comment.setContent(sanitizer.sanitize(comment.getContent()));
        comment.setStatus(1);
        comment.setUpvotes(0);
        if (comment.getParentId() == null) {
            comment.setParentId(0L);
        }
        return super.save(comment);
    }

    @Override
    public Page<CommentDisplayDTO> getCommentsPaged(String relateId, int page, int size) {
        Page<Comment> commentPage = new Page<>(page, size);
        QueryWrapper<Comment> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("relate_id", relateId)
                .eq("status", 1)
                .eq("parent_id", 0L)
                .orderByDesc("created_at");
        Page<Comment> paged = this.page(commentPage, queryWrapper);

        List<Long> rootIds = paged.getRecords().stream()
                .map(Comment::getId)
                .collect(Collectors.toList());

        // Fetch ALL non-root comments for this relateId (covers all nesting levels)
        List<Comment> allReplies = rootIds.isEmpty()
                ? Collections.emptyList()
                : this.list(new QueryWrapper<Comment>()
                        .eq("relate_id", relateId)
                        .eq("status", 1)
                        .ne("parent_id", 0L)
                        .orderByAsc("created_at"));

        // Build parent->children map
        Map<Long, List<Comment>> childrenByParent = allReplies.stream()
                .collect(Collectors.groupingBy(Comment::getParentId));

        // For each non-root comment, find its root ancestor
        Map<Long, Long> commentToRoot = new HashMap<>();
        for (Comment reply : allReplies) {
            commentToRoot.put(reply.getId(), findRootAncestor(reply.getParentId(), rootIds, childrenByParent));
        }

        // Group all replies by their root ancestor
        Map<Long, List<Comment>> repliesByRoot = new HashMap<>();
        for (Comment reply : allReplies) {
            Long rootId = commentToRoot.get(reply.getId());
            if (rootId != null) {
                repliesByRoot.computeIfAbsent(rootId, k -> new ArrayList<>()).add(reply);
            }
        }

        // Collect all user IDs
        Set<Long> userIds = new HashSet<>();
        paged.getRecords().stream()
                .map(Comment::getUserId)
                .filter(Objects::nonNull)
                .forEach(userIds::add);
        allReplies.stream()
                .map(Comment::getUserId)
                .filter(Objects::nonNull)
                .forEach(userIds::add);

        Map<Long, SysUser> userMap = userIds.isEmpty()
                ? Collections.emptyMap()
                : sysUserMapper.selectBatchIds(userIds).stream()
                        .collect(Collectors.toMap(SysUser::getId, u -> u));

        // Build a map of comment ID -> display nickname for reply-to references
        Map<Long, String> nicknameById = new HashMap<>();
        Set<Long> rootIdSet = new HashSet<>(rootIds);
        for (Comment c : paged.getRecords()) {
            nicknameById.put(c.getId(), resolveNickname(c, userMap));
        }
        for (Comment c : allReplies) {
            nicknameById.put(c.getId(), resolveNickname(c, userMap));
        }

        List<CommentDisplayDTO> records = paged.getRecords().stream().map(c -> {
            CommentDisplayDTO dto = toDisplayDTO(c, userMap);
            List<CommentDisplayDTO> replyDtos = repliesByRoot
                    .getOrDefault(c.getId(), Collections.emptyList())
                    .stream()
                    .map(reply -> {
                        CommentDisplayDTO replyDto = toDisplayDTO(reply, userMap);
                        // If this reply's parent is NOT the root, show who it replies to
                        if (reply.getParentId() != null && !rootIdSet.contains(reply.getParentId())) {
                            replyDto.setReplyToNickname(nicknameById.get(reply.getParentId()));
                        }
                        return replyDto;
                    })
                    .collect(Collectors.toList());
            dto.setReplies(replyDtos);
            return dto;
        }).collect(Collectors.toList());

        Page<CommentDisplayDTO> result = new Page<>(paged.getCurrent(), paged.getSize(), paged.getTotal());
        result.setPages(paged.getPages());
        result.setRecords(records);
        return result;
    }

    /**
     * Walk up the parent chain to find which root comment this reply belongs to.
     */
    private Long findRootAncestor(Long parentId, List<Long> rootIds, Map<Long, List<Comment>> childrenByParent) {
        Set<Long> rootSet = new HashSet<>(rootIds);
        if (rootSet.contains(parentId)) {
            return parentId;
        }
        // Walk up: find the comment with this ID and check its parent
        // Use the childrenByParent to look up comments by ID
        long current = parentId;
        int maxDepth = 20; // safety limit
        while (maxDepth-- > 0) {
            // Find the comment whose ID == current
            Comment found = null;
            for (List<Comment> children : childrenByParent.values()) {
                for (Comment c : children) {
                    if (c.getId() != null && c.getId() == current) {
                        found = c;
                        break;
                    }
                }
                if (found != null) break;
            }
            if (found == null) return null;
            if (rootSet.contains(found.getParentId())) {
                return found.getParentId();
            }
            current = found.getParentId();
        }
        return null;
    }

    /**
     * Resolve the display nickname for a comment.
     */
    private String resolveNickname(Comment comment, Map<Long, SysUser> userMap) {
        if (comment.getNickname() != null && !comment.getNickname().isBlank()) {
            return comment.getNickname();
        }
        if (comment.getUserId() != null) {
            SysUser user = userMap.get(comment.getUserId());
            if (user != null) return user.getUsername();
        }
        return "Anonymous";
    }

    private CommentDisplayDTO toDisplayDTO(Comment comment, Map<Long, SysUser> userMap) {
        CommentDisplayDTO dto = new CommentDisplayDTO();
        BeanUtils.copyProperties(comment, dto);
        if (comment.getUserId() != null) {
            SysUser user = userMap.get(comment.getUserId());
            if (user != null) {
                dto.setUsername(user.getUsername());
                if (comment.getNickname() == null || comment.getNickname().isBlank()) {
                    dto.setNickname(user.getUsername());
                }
            }
        }
        if (comment.getNickname() != null && !comment.getNickname().isBlank()) {
            dto.setNickname(comment.getNickname());
        }
        return dto;
    }
}
