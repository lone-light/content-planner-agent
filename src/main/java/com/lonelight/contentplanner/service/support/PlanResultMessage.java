package com.lonelight.contentplanner.service.support;

import com.lonelight.contentplanner.common.exception.BusinessException;
import com.lonelight.contentplanner.model.agent.AgentState;
import com.lonelight.contentplanner.model.enums.TaskStatus;
import com.lonelight.contentplanner.model.plan.CostQuote;
import com.lonelight.contentplanner.model.plan.SavedPlan;
import java.math.BigDecimal;

/** 业务结果只陈述账本和持久化事实；原始模型理由由 Trace 保留。 */
public final class PlanResultMessage {
    private PlanResultMessage() {}

    public static String render(AgentState state, CostQuote current, SavedPlan saved) {
        if (state.getStatus() == TaskStatus.RUNNING) return "任务执行中";
        if (state.getStatus() == TaskStatus.NEEDS_INPUT) {
            return "任务需要补充信息，方案尚未完成；请补充："
                    + (state.getMissingFields().isEmpty()
                            ? "与主题相关的素材或要求"
                            : String.join("、", state.getMissingFields()));
        }
        String cost =
                "已发生费用 "
                        + number(state.spentUnits())
                        + "，预算 "
                        + number(state.getRequest().budgetUnits());
        if (state.getStatus() == TaskStatus.COMPLETED) {
            if (saved == null) {
                throw new BusinessException(503, "STATE_INCONSISTENT", "任务状态与保存记录不一致，不能确认已完成");
            }
            return "方案已保存（" + saved.savedPlanId() + "）；" + cost + "。";
        }
        String detail;
        if ("STEP_LIMIT".equals(state.getReasonCode())) {
            detail = "达到本轮决策次数上限";
        } else if ("DEADLINE_EXPIRED".equals(state.getReasonCode())) {
            detail = "截止时间已经到达或过期";
        } else if (state.getLastObservation() != null && !state.getLastObservation().success()) {
            detail = state.getLastObservation().message();
        } else if (current != null && !current.problems().isEmpty()) {
            detail = String.join("；", current.problems());
        } else {
            detail = "模型结束本轮并报告未完成";
        }
        String remaining =
                saved == null && current != null
                        ? "；当前方案尚需 "
                                + number(current.remainingUnits())
                                + "，预计总费用 "
                                + number(current.projectedTotalUnits())
                        : "";
        return "任务未完成；"
                + (saved == null ? "方案尚未保存" : "方案已保存，等待完成确认")
                + "；"
                + detail
                + "；"
                + cost
                + remaining
                + "。";
    }

    private static String number(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }
}
