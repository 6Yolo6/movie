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
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

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
        String sanitized = sanitizer.sanitize(comment.getContent());
        if (sanitized == null || sanitized.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Comment content is empty after sanitization");
        }
        comment.setContent(sanitized);
        comment.setStatus(1);
        comment.setUpvotes(0);
        if (comment.getParentId() == null) {
            comment.setParentId(0L);
        }
        return super.save(comment);
    }

    @Override
    public Page<CommentDisplayDTO> getCommentsPaged(String relateId, String type, int page, int size) {
        Page<Comment> commentPage = new Page<>(page, size);
        QueryWrapper<Comment> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("relate_id", relateId)
                .eq(type != null && !type.isBlank(), "comment_type", type)
                .eq("status", 1)
                .eq("parent_id", 0L)
                .orderByDesc("created_at");
        Page<Comment> paged = this.page(commentPage, queryWrapper);

        Set<Long> rootIds = paged.getRecords().stream()
                .map(Comment::getId)
                .collect(Collectors.toSet());

        // Fetch ALL non-root comments for this relateId (covers all nesting levels)
        List<Comment> allReplies = rootIds.isEmpty()
                ? Collections.emptyList()
                : this.list(new QueryWrapper<Comment>()
                        .eq("relate_id", relateId)
                        .eq(type != null && !type.isBlank(), "comment_type", type)
                        .eq("status", 1)
                        .ne("parent_id", 0L)
                        .orderByAsc("created_at"));

        // Index once: walking an ancestor chain must not scan every reply at each hop.
        Map<Long, Comment> repliesById = new HashMap<>();
        for (Comment reply : allReplies) {
            repliesById.put(reply.getId(), reply);
        }

        // Retain the database's chronological order, but only enrich visible threads.
        List<Comment> visibleReplies = new ArrayList<>();
        Map<Long, List<Comment>> repliesByRoot = new HashMap<>();
        for (Comment reply : allReplies) {
            Long rootId = findRootAncestor(reply.getParentId(), rootIds, repliesById);
            if (rootId != null) {
                repliesByRoot.computeIfAbsent(rootId, k -> new ArrayList<>()).add(reply);
                visibleReplies.add(reply);
            }
        }

        // Fetch profiles only for roots and replies that will be returned.
        Set<Long> userIds = new HashSet<>();
        paged.getRecords().stream()
                .map(Comment::getUserId)
                .filter(Objects::nonNull)
                .forEach(userIds::add);
        visibleReplies.stream()
                .map(Comment::getUserId)
                .filter(Objects::nonNull)
                .forEach(userIds::add);

        Map<Long, SysUser> userMap = userIds.isEmpty()
                ? Collections.emptyMap()
                : sysUserMapper.selectBatchIds(userIds).stream()
                        .collect(Collectors.toMap(SysUser::getId, u -> u));

        // Build a map of comment ID -> display nickname for reply-to references
        Map<Long, String> nicknameById = new HashMap<>();
        for (Comment c : paged.getRecords()) {
            nicknameById.put(c.getId(), resolveNickname(c, userMap));
        }
        for (Comment c : visibleReplies) {
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
                        if (reply.getParentId() != null && !rootIds.contains(reply.getParentId())) {
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
    private Long findRootAncestor(Long parentId, Set<Long> rootIds, Map<Long, Comment> repliesById) {
        if (rootIds.contains(parentId)) {
            return parentId;
        }
        Long current = parentId;
        int maxDepth = 20; // Keep the existing safety limit for cycles/deep legacy threads.
        while (current != null && maxDepth-- > 0) {
            Comment found = repliesById.get(current);
            if (found == null) return null;
            if (rootIds.contains(found.getParentId())) {
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
        if (comment.getUserId() != null) {
            SysUser user = userMap.get(comment.getUserId());
            if (user != null) return user.displayName();
        }
        if (comment.getNickname() != null && !comment.getNickname().isBlank()) {
            return comment.getNickname();
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
                dto.setNickname(user.displayName());
                return dto;
            }
        }
        if (comment.getNickname() != null && !comment.getNickname().isBlank()) {
            dto.setNickname(comment.getNickname());
        }
        return dto;
    }
}
