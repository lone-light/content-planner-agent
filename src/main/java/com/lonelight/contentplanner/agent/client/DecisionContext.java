package com.lonelight.contentplanner.agent.client;

import com.lonelight.contentplanner.model.agent.AgentState;
import com.lonelight.contentplanner.model.plan.CostQuote;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

public record DecisionContext(
        AgentState state,
        int step,
        int maxSteps,
        OffsetDateTime businessTime,
        boolean resumed,
        CurrentAssessment currentAssessment) {
    /** 当前状态的费用评估，不生成可用于执行工具的新报价凭证。 */
    public record CurrentAssessment(
            String planId,
            BigDecimal remainingEstimatedUnits,
            BigDecimal projectedTotalUnits,
            int remainingMinutes,
            OffsetDateTime estimatedFinishTime,
            boolean feasible,
            BigDecimal budgetGapUnits,
            long deadlineGapMinutes,
            List<String> problems) {
        public static CurrentAssessment from(CostQuote quote) {
            return new CurrentAssessment(
                    quote.planId(),
                    quote.remainingUnits(),
                    quote.projectedTotalUnits(),
                    quote.remainingMinutes(),
                    quote.estimatedFinishTime(),
                    quote.feasible(),
                    quote.budgetGapUnits(),
                    quote.deadlineGapMinutes(),
                    quote.problems());
        }
    }
}
