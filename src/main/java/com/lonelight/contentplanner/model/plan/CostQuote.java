package com.lonelight.contentplanner.model.plan;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

public record CostQuote(
        String quoteId,
        String planId,
        BigDecimal spentUnits,
        BigDecimal remainingUnits,
        BigDecimal projectedTotalUnits,
        int remainingMinutes,
        OffsetDateTime estimatedFinishTime,
        boolean feasible,
        BigDecimal budgetGapUnits,
        long deadlineGapMinutes,
        List<String> problems) {}
