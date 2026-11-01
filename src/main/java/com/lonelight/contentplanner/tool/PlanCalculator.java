package com.lonelight.contentplanner.tool;

import com.lonelight.contentplanner.common.json.JsonCodec;
import com.lonelight.contentplanner.model.agent.AgentState;
import com.lonelight.contentplanner.model.plan.CostQuote;
import com.lonelight.contentplanner.model.plan.PlanCandidate;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class PlanCalculator {
    private final ToolCatalog catalog;
    private final JsonCodec json;
    private final Clock clock;

    public PlanCalculator(ToolCatalog catalog, JsonCodec json, Clock clock) {
        this.catalog = catalog;
        this.json = json;
        this.clock = clock;
    }

    public OffsetDateTime businessTime(AgentState state) {
        OffsetDateTime actual = OffsetDateTime.now(clock);
        OffsetDateTime simulated = state.getStartTime().plusMinutes(state.getElapsedMinutes());
        return actual.isAfter(simulated) ? actual : simulated;
    }

    public CostQuote calculate(AgentState state, PlanCandidate plan) {
        BigDecimal remaining = BigDecimal.ZERO;
        int minutes = 0;
        if (state.getSavedPlan() == null) {
            remaining = catalog.getRates().save().costUnits();
            minutes = catalog.getRates().save().durationMinutes();
            if (!state.getScripts().containsKey(plan.planId())) {
                var generate = catalog.getRates().generate().get(plan.planMode());
                remaining = remaining.add(generate.costUnits());
                minutes += generate.durationMinutes();
            }
        }
        BigDecimal spent = state.spentUnits();
        BigDecimal total = spent.add(remaining);
        BigDecimal budgetGap =
                total.subtract(state.getRequest().budgetUnits()).max(BigDecimal.ZERO);
        OffsetDateTime finish = businessTime(state).plusMinutes(minutes);
        boolean late = finish.isAfter(state.getRequest().deadlineTime());
        long gapMinutes =
                late
                        ? Math.max(
                                1,
                                (Duration.between(state.getRequest().deadlineTime(), finish)
                                                        .toSeconds()
                                                + 59)
                                        / 60)
                        : 0;
        List<String> problems = new ArrayList<>();
        if (budgetGap.signum() > 0) problems.add("预算不足，缺少 " + budgetGap.toPlainString() + " 个预算单位");
        if (late) problems.add("截止时间不足，预计超出 " + gapMinutes + " 分钟");
        if (state.getSavedPlan() != null
                && !state.getSavedPlan().plan().planId().equals(plan.planId())) {
            problems.add("该请求已保存不同方案，禁止覆盖");
        }
        String quoteId =
                "quote-"
                        + json.hash(
                                        List.of(
                                                plan,
                                                spent,
                                                state.getElapsedMinutes(),
                                                remaining,
                                                finish))
                                .substring(0, 16);
        return new CostQuote(
                quoteId,
                plan.planId(),
                spent,
                remaining,
                total,
                minutes,
                finish,
                problems.isEmpty(),
                budgetGap,
                gapMinutes,
                List.copyOf(problems));
    }
}
