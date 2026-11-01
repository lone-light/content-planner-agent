package com.lonelight.contentplanner.model.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.lonelight.contentplanner.model.enums.ToolName;

public record ToolAttempt(
        String runId,
        int step,
        ToolName tool,
        JsonNode arguments,
        String status,
        ToolObservation observation) {}
