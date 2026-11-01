package com.lonelight.contentplanner.tool;

import com.lonelight.contentplanner.common.json.JsonCodec;
import com.lonelight.contentplanner.model.agent.AgentAction;
import com.lonelight.contentplanner.model.agent.AgentState;
import com.lonelight.contentplanner.model.enums.PlanMode;
import com.lonelight.contentplanner.model.enums.ToolName;
import com.lonelight.contentplanner.model.plan.CostQuote;
import com.lonelight.contentplanner.model.plan.PlanCandidate;
import com.lonelight.contentplanner.model.plan.SavedPlan;
import com.lonelight.contentplanner.model.plan.ScriptArtifact;
import com.lonelight.contentplanner.model.tool.ToolObservation;
import jakarta.validation.Validator;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class ToolExecutor {
    private final ToolCatalog catalog;
    private final PlanCalculator calculator;
    private final JsonCodec json;
    private final Validator validator;
    private final Clock clock;

    public ToolExecutor(
            ToolCatalog catalog,
            PlanCalculator calculator,
            JsonCodec json,
            Validator validator,
            Clock clock) {
        this.catalog = catalog;
        this.calculator = calculator;
        this.json = json;
        this.validator = validator;
        this.clock = clock;
    }

    public Execution execute(AgentAction action, AgentState state) {
        try {
            if (action.arguments() == null || !action.arguments().isObject()) {
                return error("INVALID_ARGUMENTS", "工具参数必须为 JSON 对象");
            }
            return switch (action.tool()) {
                case SEARCH_ASSETS -> search(arguments(action, SearchArguments.class), state);
                case ESTIMATE_PLAN -> estimate(arguments(action, EstimateArguments.class), state);
                case GENERATE_SCRIPT -> generate(arguments(action, GenerateArguments.class), state);
                case SAVE_PLAN -> save(arguments(action, SaveArguments.class), state);
            };
        } catch (IllegalArgumentException error) {
            return error("INVALID_ARGUMENTS", "工具参数无效：" + error.getMessage());
        }
    }

    private <T> T arguments(AgentAction action, Class<T> type) {
        T parsed = json.convert(action.arguments(), type);
        var violations = validator.validate(parsed);
        if (!violations.isEmpty()) {
            throw new IllegalArgumentException(
                    violations.stream()
                            .map(v -> v.getPropertyPath() + " " + v.getMessage())
                            .sorted()
                            .findFirst()
                            .orElse("参数不合法"));
        }
        return parsed;
    }

    private Execution search(SearchArguments args, AgentState state) {
        String query = args.query().strip().toLowerCase(Locale.ROOT);
        String key = "search:" + json.hash(List.of(query, catalog.getVersion()));
        if (state.getCache().containsKey(key)) return reused(state.getCache().get(key));
        var rate = catalog.getRates().search();
        Execution blocked = guardSingle(state, rate);
        if (blocked != null) return blocked;
        Execution failure = injectedFailure(ToolName.SEARCH_ASSETS, state, rate);
        if (failure != null) return failure;
        var matches =
                catalog.getMaterials().items().stream()
                        .filter(
                                asset ->
                                        asset.tags().stream()
                                                .anyMatch(
                                                        tag ->
                                                                query.contains(
                                                                        tag.toLowerCase(
                                                                                Locale.ROOT))))
                        .sorted(Comparator.comparing(ToolCatalog.Asset::id))
                        .toList();
        matches.forEach(asset -> state.getAssets().put(asset.id(), json.tree(asset)));
        ToolObservation result = success(Map.of("assets", matches, "count", matches.size()), rate);
        state.getCache().put(key, result);
        return new Execution(result, null, false);
    }

    private Execution estimate(EstimateArguments args, AgentState state) {
        var ids = args.assetIds().stream().distinct().sorted().toList();
        if (!ids.stream().allMatch(state.getAssets()::containsKey))
            return error("ASSET_NOT_FOUND", "只能使用已经检索取得的素材 ID");
        Execution failure =
                injectedFailure(
                        ToolName.ESTIMATE_PLAN,
                        state,
                        new ToolCatalog.ToolRate(BigDecimal.ZERO, 0));
        if (failure != null) return failure;
        String id =
                "plan-"
                        + json.hash(
                                        List.of(
                                                state.getRequest().requestId(),
                                                args.planMode(),
                                                ids,
                                                catalog.getVersion()))
                                .substring(0, 16);
        PlanCandidate plan = new PlanCandidate(id, args.planMode(), ids);
        CostQuote quote = calculator.calculate(state, plan);
        state.getPlans().put(id, plan);
        state.setActivePlanId(id);
        state.getQuotes().put(quote.quoteId(), quote);
        return new Execution(
                new ToolObservation(
                        true,
                        "OK",
                        "报价已计算；可行性以 feasible 为准",
                        json.tree(Map.of("plan", plan, "quote", quote)),
                        BigDecimal.ZERO,
                        0,
                        false,
                        false),
                null,
                false);
    }

    private Execution generate(GenerateArguments args, AgentState state) {
        PlanCandidate plan = state.getPlans().get(args.planId());
        CostQuote quote = state.getQuotes().get(args.quoteId());
        if (plan == null || quote == null || !quote.planId().equals(plan.planId()))
            return error("QUOTE_REQUIRED", "请先获取该方案的报价和有效 planId/quoteId");
        state.setActivePlanId(plan.planId());
        if (state.getScripts().containsKey(plan.planId())) {
            return new Execution(
                    new ToolObservation(
                            true,
                            "OK",
                            "复用已成功生成的脚本",
                            json.tree(state.getScripts().get(plan.planId())),
                            BigDecimal.ZERO,
                            0,
                            true,
                            false),
                    null,
                    false);
        }
        Execution blocked = guardPlan(state, plan);
        if (blocked != null) return blocked;
        var rate = catalog.getRates().generate().get(plan.planMode());
        Execution failure = injectedFailure(ToolName.GENERATE_SCRIPT, state, rate);
        if (failure != null) return failure;
        List<String> facts =
                plan.assetIds().stream()
                        .flatMap(
                                id ->
                                        json
                                                .convert(
                                                        state.getAssets().get(id),
                                                        ToolCatalog.Asset.class)
                                                .facts()
                                                .stream())
                        .toList();
        String title =
                state.getRequest().topic()
                        + "："
                        + (plan.planMode() == PlanMode.LEAN ? "精简活动方案" : "标准活动方案");
        String opening = "开场：今天围绕「" + state.getRequest().topic() + "」，看看这些内容。\n";
        String body = "要点：" + String.join("；", facts) + "。\n";
        String detail =
                plan.planMode() == PlanMode.STANDARD
                        ? "画面：依次呈现上述要点，对应镜头标注素材编号 " + String.join("、", plan.assetIds()) + "。\n"
                        : "";
        String ending = "结尾：你最关注哪一点？欢迎在评论区交流。";
        ScriptArtifact script =
                new ScriptArtifact(
                        "script-" + json.hash(plan).substring(0, 16),
                        plan.planId(),
                        title,
                        opening + body + detail + ending,
                        plan.assetIds(),
                        "LOCAL_SYNTHETIC_TEMPLATE");
        state.getScripts().put(plan.planId(), script);
        return new Execution(success(script, rate), null, false);
    }

    private Execution save(SaveArguments args, AgentState state) {
        PlanCandidate plan = state.getPlans().get(args.planId());
        if (plan == null) return error("PLAN_NOT_FOUND", "方案不存在，请先估算方案");
        state.setActivePlanId(plan.planId());
        if (state.getSavedPlan() != null) {
            if (!state.getSavedPlan().plan().planId().equals(plan.planId()))
                return error("PLAN_CONFLICT", "已有最终方案，禁止覆盖");
            return new Execution(
                    new ToolObservation(
                            true,
                            "OK",
                            "返回已有保存结果",
                            json.tree(state.getSavedPlan()),
                            BigDecimal.ZERO,
                            0,
                            true,
                            false),
                    null,
                    false);
        }
        ScriptArtifact script = state.getScripts().get(plan.planId());
        if (script == null) return error("DEPENDENCY_NOT_READY", "当前方案的脚本尚未成功生成");
        Execution blocked = guardPlan(state, plan);
        if (blocked != null) return blocked;
        var rate = catalog.getRates().save();
        Execution failure = injectedFailure(ToolName.SAVE_PLAN, state, rate);
        if (failure != null) return failure;
        CostQuote current = calculator.calculate(state, plan);
        SavedPlan saved =
                new SavedPlan(
                        "saved-"
                                + json.hash(
                                                List.of(
                                                        state.getModelMode(),
                                                        state.getRequest().requestId()))
                                        .substring(0, 16),
                        state.getRequest().requestId(),
                        plan,
                        List.of(
                                new SavedPlan.PlanStep("assets", "检索活动素材", List.of(), "SUCCEEDED"),
                                new SavedPlan.PlanStep(
                                        "script", "生成活动脚本", List.of("assets"), "SUCCEEDED"),
                                new SavedPlan.PlanStep(
                                        "save", "核对并保存方案", List.of("script"), "SUCCEEDED")),
                        script,
                        state.spentUnits().add(rate.costUnits()),
                        current.estimatedFinishTime(),
                        OffsetDateTime.now(clock),
                        state.getModelMode(),
                        "LOCAL_SYNTHETIC_TOOLS");
        state.setSavedPlan(saved);
        return new Execution(success(saved, rate), saved, shouldFailAfterInsert(state));
    }

    private Execution guardSingle(AgentState state, ToolCatalog.ToolRate rate) {
        if (state.spentUnits().add(rate.costUnits()).compareTo(state.getRequest().budgetUnits())
                > 0) return error("BUDGET_EXCEEDED", "本次检索费用超过剩余预算");
        if (calculator
                .businessTime(state)
                .plusMinutes(rate.durationMinutes())
                .isAfter(state.getRequest().deadlineTime()))
            return error("DEADLINE_EXCEEDED", "本次检索无法在截止时间前完成");
        return null;
    }

    private Execution guardPlan(AgentState state, PlanCandidate plan) {
        CostQuote quote = calculator.calculate(state, plan);
        if (quote.feasible()) return null;
        state.getQuotes().put(quote.quoteId(), quote);
        String code =
                quote.budgetGapUnits().signum() > 0
                        ? "BUDGET_EXCEEDED"
                        : quote.deadlineGapMinutes() > 0 ? "DEADLINE_EXCEEDED" : "PLAN_CONFLICT";
        return new Execution(
                new ToolObservation(
                        false,
                        code,
                        String.join("；", quote.problems()),
                        json.tree(quote),
                        BigDecimal.ZERO,
                        0,
                        false,
                        false),
                null,
                false);
    }

    private Execution injectedFailure(ToolName tool, AgentState state, ToolCatalog.ToolRate rate) {
        var simulation = state.getRequest().simulate();
        if (simulation == null
                || simulation.failTool() != tool
                || (tool == ToolName.SAVE_PLAN && simulation.failAfterInsert())
                || state.getInjectedFailures().getOrDefault(tool, 0) >= simulation.failures())
            return null;
        state.getInjectedFailures().merge(tool, 1, Integer::sum);
        return new Execution(
                new ToolObservation(
                        false,
                        "TOOL_UNAVAILABLE",
                        "工具暂时不可用，请稍后重试",
                        null,
                        BigDecimal.ZERO,
                        rate.durationMinutes(),
                        false,
                        true),
                null,
                false);
    }

    private boolean shouldFailAfterInsert(AgentState state) {
        var simulation = state.getRequest().simulate();
        return simulation != null
                && simulation.failTool() == ToolName.SAVE_PLAN
                && simulation.failAfterInsert()
                && state.getInjectedFailures().getOrDefault(ToolName.SAVE_PLAN, 0)
                        < simulation.failures();
    }

    public ToolObservation saveFailure() {
        return new ToolObservation(
                false,
                "SAVE_FAILED",
                "方案写入已回滚，尚未保存",
                null,
                BigDecimal.ZERO,
                catalog.getRates().save().durationMinutes(),
                false,
                true);
    }

    private ToolObservation success(Object data, ToolCatalog.ToolRate rate) {
        return new ToolObservation(
                true,
                "OK",
                "工具执行成功",
                json.tree(data),
                rate.costUnits(),
                rate.durationMinutes(),
                false,
                false);
    }

    private Execution reused(ToolObservation previous) {
        return new Execution(
                new ToolObservation(
                        true,
                        previous.code(),
                        "复用成功结果，未重复收费",
                        previous.data(),
                        BigDecimal.ZERO,
                        0,
                        true,
                        false),
                null,
                false);
    }

    private Execution error(String code, String message) {
        return new Execution(ToolObservation.error(code, message, false), null, false);
    }

    public record Execution(
            ToolObservation observation, SavedPlan newPlan, boolean failSaveAfterInsert) {}

    public record SearchArguments(@NotBlank @Size(max = 500) String query) {}

    public record EstimateArguments(
            @NotNull PlanMode planMode,
            @NotNull @Size(min = 1, max = 20) List<@NotBlank String> assetIds) {}

    public record GenerateArguments(@NotBlank String planId, @NotBlank String quoteId) {}

    public record SaveArguments(@NotBlank String planId) {}
}
