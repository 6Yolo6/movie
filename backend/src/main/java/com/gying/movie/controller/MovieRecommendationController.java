package com.gying.movie.controller;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gying.movie.service.impl.CatalogAiService;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/movies")
public class MovieRecommendationController {
    private final CatalogAiService catalog;
    public MovieRecommendationController(CatalogAiService catalog) { this.catalog = catalog; }

    @GetMapping("/{id}/recommendations")
    public List<ObjectNode> recommendations(@PathVariable String id,
            @RequestParam(defaultValue = "8") int limit) {
        return catalog.related(id, limit);
    }
}
