package com.lonelight.contentplanner.agent.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.lonelight.contentplanner.model.agent.AgentAction;

public interface ModelClient {
    ModelReply decide(DecisionContext context);

    String identity();

    record ModelReply(AgentAction action, JsonNode metadata) {}
}
