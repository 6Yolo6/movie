package com.gying.movie.service;

import com.fasterxml.jackson.databind.JsonNode;

public interface IQqBotService {
    boolean handleOneBotEvent(JsonNode event);

    String buildSearchReply(String keyword);

    /** Send an unsolicited message to a configured QQ group. */
    default void sendGroupMessage(Long groupId, String message) {
        throw new UnsupportedOperationException("QQ group push is not supported");
    }

    default String buildSearchReply(String keyword, String userKey) {
        return buildSearchReply(keyword);
    }
}
