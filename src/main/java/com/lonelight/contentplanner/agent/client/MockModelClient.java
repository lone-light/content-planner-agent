package com.lonelight.contentplanner.agent.client;

import com.lonelight.contentplanner.common.json.JsonCodec;
import com.lonelight.contentplanner.model.agent.AgentAction;
import com.lonelight.contentplanner.model.agent.AgentState;
import com.lonelight.contentplanner.model.enums.PlanMode;
import com.lonelight.contentplanner.model.enums.TaskStatus;
import com.lonelight.contentplanner.model.enums.ToolName;
import com.lonelight.contentplanner.model.plan.CostQuote;
import com.lonelight.contentplanner.model.tool.ToolAttempt;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 离线流程替身。其规则决策不证明真实模型的 ReAct 效果。 */
public class MockModelClient implements ModelClient {
    private final JsonCodec json;

    public MockModelClient(JsonCodec json) {
        this.json = json;
    }

    @Override
    public String identity() {
        return "offline-rule-mock-v1";
    }

    @Override
    public ModelReply decide(DecisionContext context) {
        AgentState state = context.state();
        if (state.getSavedPlan() != null)
            return finish(TaskStatus.COMPLETED, "工具已返回保存结果，结束任务", List.of());
        var last = state.getLastObservation();
        if (last != null && !last.success() && last.retryable()) {
            var failed =
                    state.getAttempts().stream()
                            .filter(attempt -> !attempt.observation().success())
                            .toList();
            if (!failed.isEmpty()) {
                ToolAttempt previous = failed.getLast();
                long currentFailures =
                        failed.stream()
                                .filter(
                                        attempt ->
                                                attempt.runId().equals(state.getCurrentRunId())
                                                        && attempt.tool() == previous.tool())
                                .count();
                if (currentFailures >= 2)
                    return finish(TaskStatus.INCOMPLETE, "本次运行工具持续失败，保留已成功步骤供续跑", List.of());
                return reply(
                        new AgentAction(
                                AgentAction.Type.TOOL_CALL,
                                previous.tool(),
                                previous.arguments(),
                                null,
                                "观察到可重试的工具错误，复用成功步骤并重试失败动作",
                                List.of()));
            }
        }
        if (state.getAssets().isEmpty()) {
            long searches =
                    state.getAttempts().stream()
                            .filter(
                                    attempt ->
                                            attempt.tool() == ToolName.SEARCH_ASSETS
                                                    && attempt.observation().success())
                            .count();
            if (last != null && !last.success() && !last.retryable())
                return finish(TaskStatus.INCOMPLETE, "检索受到预算或截止时间约束，无法继续", List.of());
            if (searches >= 2)
                return finish(TaskStatus.NEEDS_INPUT, "检索未获得相关素材，请补充素材或更明确的主题", List.of("assets"));
            String query = state.getRequest().topic();
            if (searches == 1) {
                query = query.replaceAll("[，。！？、\\s]|请|帮我|制作|活动|方案|宣传", "");
                if (query.isBlank())
                    return finish(
                            TaskStatus.NEEDS_INPUT,
                            "主题不足以检索，请补充具体产品信息",
                            List.of("topic", "assets"));
            }
            return tool(
                    ToolName.SEARCH_ASSETS,
                    Map.of("query", query),
                    searches == 0 ? "先检索主题相关素材" : "未检索到素材，精简查询后补查");
        }
        Map<PlanMode, CostQuote> latest = new LinkedHashMap<>();
        state.getQuotes()
                .values()
                .forEach(
                        quote ->
                                latest.put(state.getPlans().get(quote.planId()).planMode(), quote));
        PlanMode preferred = state.getRequest().effectivePreferredMode();
        if (!latest.containsKey(preferred))
            return estimate(state, preferred, "先估算用户偏好的方案，不直接执行收费生成");
        CostQuote selected = latest.get(preferred);
        if (!selected.feasible()) {
            PlanMode alternative =
                    preferred == PlanMode.STANDARD ? PlanMode.LEAN : PlanMode.STANDARD;
            if (!latest.containsKey(alternative))
                return estimate(state, alternative, "已观察到报价不可行，查询替代方案");
            selected = latest.get(alternative);
            if (!selected.feasible())
                return finish(TaskStatus.INCOMPLETE, "已查询两种方案，均无法满足当前预算或截止时间", List.of());
        }
        if (!state.getScripts().containsKey(selected.planId()))
            return tool(
                    ToolName.GENERATE_SCRIPT,
                    Map.of("planId", selected.planId(), "quoteId", selected.quoteId()),
                    "采用工具确认可行的方案生成脚本");
        return tool(ToolName.SAVE_PLAN, Map.of("planId", selected.planId()), "脚本已成功，保存方案及实际费用");
    }

    private ModelReply estimate(AgentState state, PlanMode mode, String reason) {
        return tool(
                ToolName.ESTIMATE_PLAN,
                Map.of("planMode", mode, "assetIds", List.copyOf(state.getAssets().keySet())),
                reason);
    }

    private ModelReply tool(ToolName tool, Object args, String reason) {
        return reply(
                new AgentAction(
                        AgentAction.Type.TOOL_CALL,
                        tool,
                        json.tree(args),
                        null,
                        reason,
                        List.of()));
    }

    private ModelReply finish(TaskStatus status, String reason, List<String> missing) {
        return reply(new AgentAction(AgentAction.Type.FINISH, null, null, status, reason, missing));
    }

    private ModelReply reply(AgentAction action) {
        return new ModelReply(
                action, json.tree(Map.of("identity", identity(), "realModel", false)));
    }
}
