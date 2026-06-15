package com.gying.movie.dto;

import com.gying.movie.entity.ResourceLink;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class ResourceAdminDTO extends ResourceLink {
    private String movieTitle;
    private String uploaderName;
}
