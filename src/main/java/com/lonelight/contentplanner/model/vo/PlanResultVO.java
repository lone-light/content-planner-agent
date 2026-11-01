package com.lonelight.contentplanner.model.vo;

import com.lonelight.contentplanner.model.enums.TaskStatus;
import com.lonelight.contentplanner.model.plan.CostQuote;
import com.lonelight.contentplanner.model.plan.PlanCandidate;
import com.lonelight.contentplanner.model.plan.SavedPlan;
import com.lonelight.contentplanner.model.tool.ToolAttempt;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

public record PlanResultVO(
        String requestId,
        TaskStatus status,
        String reasonCode,
        String message,
        List<String> missingFields,
        String modelMode,
        String modelIdentity,
        String toolsProvenance,
        String dataVersion,
        BigDecimal budgetUnits,
        BigDecimal spentUnits,
        BigDecimal remainingEstimatedUnits,
        BigDecimal projectedTotalUnits,
        OffsetDateTime deadlineTime,
        OffsetDateTime estimatedFinishTime,
        int elapsedSimulatedMinutes,
        int totalModelSteps,
        boolean saved,
        SavedPlan savedPlan,
        List<SavedPlan.PlanStep> taskPlan,
        List<PlanCandidate> candidates,
        List<CostQuote> quotes,
        List<ToolAttempt> steps,
        boolean replayed,
        boolean resumed) {}
