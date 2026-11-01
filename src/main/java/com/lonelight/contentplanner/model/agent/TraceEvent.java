package com.lonelight.contentplanner.model.agent;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;

public record TraceEvent(
        long sequence,
        String runId,
        int step,
        OffsetDateTime recordTime,
        String modelMode,
        String type,
        JsonNode detail) {}
