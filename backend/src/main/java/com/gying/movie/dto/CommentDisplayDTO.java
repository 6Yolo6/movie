package com.gying.movie.dto;

import com.gying.movie.entity.Comment;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.ArrayList;
import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
public class CommentDisplayDTO extends Comment {
    private String username;
    private String replyToNickname;
    private List<CommentDisplayDTO> replies = new ArrayList<>();
}
