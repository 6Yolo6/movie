package com.gying.movie.service;

import com.fasterxml.jackson.databind.JsonNode;

public interface IQqBotService {
    boolean handleOneBotEvent(JsonNode event);

    String buildSearchReply(String keyword);
}
