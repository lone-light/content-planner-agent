package com.lonelight.contentplanner.model.tool;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;

public record ToolObservation(
        boolean success,
        String code,
        String message,
        JsonNode data,
        BigDecimal chargedUnits,
        int durationMinutes,
        boolean reused,
        boolean retryable) {
    public static ToolObservation error(String code, String message, boolean retryable) {
        return new ToolObservation(
                false, code, message, null, BigDecimal.ZERO, 0, false, retryable);
    }
}
