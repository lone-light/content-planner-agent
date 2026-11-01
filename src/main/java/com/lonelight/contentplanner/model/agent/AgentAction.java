package com.lonelight.contentplanner.model.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.lonelight.contentplanner.model.enums.TaskStatus;
import com.lonelight.contentplanner.model.enums.ToolName;
import java.util.List;

public record AgentAction(
        Type type,
        ToolName tool,
        JsonNode arguments,
        TaskStatus status,
        String reason,
        List<String> missingFields) {
    public enum Type {
        TOOL_CALL,
        FINISH
    }
}
