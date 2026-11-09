package com.gying.movie.service.impl;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** A permanent exact-case alias; aliases are flattened in the merge transaction. */
@Service
public class MovieMergeAliasResolver {
    private final JdbcTemplate jdbc;
    public MovieMergeAliasResolver(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public String resolve(String id) {
        if (id == null) return null;
        var rows = jdbc.queryForList("SELECT canonical_movie_id FROM movie_merge_alias WHERE source_movie_id=?", String.class, id);
        return rows.isEmpty() ? id : rows.get(0);
    }
}
