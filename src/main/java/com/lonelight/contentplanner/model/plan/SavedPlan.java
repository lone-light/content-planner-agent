package com.lonelight.contentplanner.model.plan;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

public record SavedPlan(
        String savedPlanId,
        String requestId,
        PlanCandidate plan,
        List<PlanStep> steps,
        ScriptArtifact script,
        BigDecimal totalCostUnits,
        OffsetDateTime estimatedFinishTime,
        OffsetDateTime saveTime,
        String modelMode,
        String toolsProvenance) {
    public record PlanStep(String id, String title, List<String> dependsOn, String status) {}
}
