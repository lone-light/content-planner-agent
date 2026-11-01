package com.lonelight.contentplanner.service.impl;

import com.lonelight.contentplanner.agent.client.ModelClient;
import com.lonelight.contentplanner.agent.runtime.AgentRunner;
import com.lonelight.contentplanner.common.exception.BusinessException;
import com.lonelight.contentplanner.common.json.JsonCodec;
import com.lonelight.contentplanner.config.AgentProperties;
import com.lonelight.contentplanner.model.agent.AgentState;
import com.lonelight.contentplanner.model.agent.StoredTask;
import com.lonelight.contentplanner.model.dto.PlanRequest;
import com.lonelight.contentplanner.model.enums.TaskStatus;
import com.lonelight.contentplanner.model.enums.ToolName;
import com.lonelight.contentplanner.model.plan.SavedPlan;
import com.lonelight.contentplanner.model.tool.ToolAttempt;
import com.lonelight.contentplanner.model.vo.PlanResultVO;
import com.lonelight.contentplanner.service.PlanService;
import com.lonelight.contentplanner.service.TaskPersistenceService;
import com.lonelight.contentplanner.service.support.PlanResultMessage;
import com.lonelight.contentplanner.tool.PlanCalculator;
import com.lonelight.contentplanner.tool.ToolCatalog;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class PlanServiceImpl implements PlanService {
    private final TaskPersistenceService store;
    private final AgentRunner runner;
    private final AgentProperties properties;
    private final ToolCatalog catalog;
    private final PlanCalculator calculator;
    private final ModelClient model;
    private final JsonCodec json;
    private final Clock clock;
    private final Object[] requestLocks = new Object[64];

    public PlanServiceImpl(
            TaskPersistenceService store,
            AgentRunner runner,
            AgentProperties properties,
            ToolCatalog catalog,
            PlanCalculator calculator,
            ModelClient model,
            JsonCodec json,
            Clock clock) {
        this.store = store;
        this.runner = runner;
        this.properties = properties;
        this.catalog = catalog;
        this.calculator = calculator;
        this.model = model;
        this.json = json;
        this.clock = clock;
        Arrays.setAll(requestLocks, index -> new Object());
    }

    @Override
    public PlanResultVO submit(PlanRequest request) {
        validateSimulation(request);
        synchronized (lock(request.requestId())) {
            String hash = fingerprint(request);
            var existing = store.find(properties.getModelMode(), request.requestId());
            if (existing.isPresent()) {
                if (!existing.get().payloadHash().equals(hash)) throw conflict();
                return replay(existing.get().state());
            }
            AgentState state = new AgentState();
            state.setRequest(request);
            state.setModelMode(properties.getModelMode());
            state.setModelIdentity(model.identity());
            state.setDataVersion(catalog.getVersion());
            state.setStartTime(OffsetDateTime.now(clock));
            state.setCurrentRunId(UUID.randomUUID().toString());
            store.create(state, hash);
            if (preflight(state)) return view(state, false);
            return view(runner.run(state, false), false);
        }
    }

    @Override
    public PlanResultVO resume(String requestId) {
        synchronized (lock(requestId)) {
            var stored = require(requestId);
            AgentState state = stored.state();
            if (!stored.payloadHash().equals(fingerprint(state.getRequest()))) throw conflict();
            if (state.getStatus() == TaskStatus.COMPLETED) return replay(state);
            validateSimulation(state.getRequest());
            if (preflight(state)) return view(state, true);
            return view(runner.run(state, true), true);
        }
    }

    @Override
    public PlanResultVO get(String requestId) {
        return view(require(requestId).state(), false);
    }

    @Override
    public String trace(String requestId) {
        return require(requestId).state().getTrace().stream()
                .map(json::write)
                .reduce("", (all, line) -> all + line + "\n");
    }

    private PlanResultVO replay(AgentState state) {
        var saved =
                store.savedPlan(state.getModelMode(), state.getRequest().requestId()).orElse(null);
        if (state.getStatus() == TaskStatus.COMPLETED
                && runner.completionProblem(state, saved) != null) {
            throw new BusinessException(503, "STATE_INCONSISTENT", "保存记录与任务状态不一致，不能确认已完成");
        }
        state.setCurrentRunId(UUID.randomUUID().toString());
        runner.event(state, 0, "REPLAYED", Map.of("message", "返回已持久化结果，未调用模型或重复执行工具"));
        store.checkpoint(state);
        return view(state, true, false, saved);
    }

    private boolean preflight(AgentState state) {
        var missing = state.getRequest().missingFields();
        if (!missing.isEmpty()) {
            state.setStatus(TaskStatus.NEEDS_INPUT);
            state.setMissingFields(missing);
            state.setReasonCode("MISSING_INPUT");
            state.setMessage("请补充：" + String.join("、", missing));
        } else if (state.getSavedPlan() == null
                && !state.getRequest().deadlineTime().isAfter(OffsetDateTime.now(clock))) {
            state.setStatus(TaskStatus.INCOMPLETE);
            state.setReasonCode("DEADLINE_EXPIRED");
            state.setMessage("截止时间已经到达或过期，未执行收费工具");
        } else return false;
        runner.event(
                state,
                0,
                "INPUT_CHECK",
                Map.of(
                        "status",
                        state.getStatus(),
                        "reasonCode",
                        state.getReasonCode(),
                        "missingFields",
                        state.getMissingFields()));
        store.checkpoint(state);
        return true;
    }

    private void validateSimulation(PlanRequest request) {
        var simulation = request.simulate();
        if (simulation == null) return;
        if (!properties.isSimulationEnabled())
            throw new BusinessException(400, "SIMULATION_DISABLED", "故障注入未启用");
        if (simulation.failures() > 0 && simulation.failTool() == null)
            throw new BusinessException(400, "INVALID_SIMULATION", "故障注入必须指定工具");
        if (simulation.failAfterInsert() && simulation.failTool() != ToolName.SAVE_PLAN)
            throw new BusinessException(400, "INVALID_SIMULATION", "事务回滚注入只适用于保存工具");
    }

    private String fingerprint(PlanRequest request) {
        return json.hash(
                Map.of(
                        "request",
                        request,
                        "dataVersion",
                        catalog.getVersion(),
                        "modelIdentity",
                        model.identity()));
    }

    private BusinessException conflict() {
        return new BusinessException(
                409, "IDEMPOTENCY_CONFLICT", "同一请求 ID 的输入、模型或资料版本不同；请使用新的 requestId");
    }

    private StoredTask require(String requestId) {
        return store.find(properties.getModelMode(), requestId)
                .orElseThrow(() -> new BusinessException(404, "TASK_NOT_FOUND", "任务不存在"));
    }

    private Object lock(String requestId) {
        return requestLocks[
                Math.floorMod(
                        (properties.getModelMode() + ":" + requestId).hashCode(),
                        requestLocks.length)];
    }

    private PlanResultVO view(AgentState state, boolean resumed) {
        var saved =
                store.savedPlan(state.getModelMode(), state.getRequest().requestId()).orElse(null);
        return view(state, false, resumed, saved);
    }

    private PlanResultVO view(
            AgentState state, boolean replayed, boolean resumed, SavedPlan saved) {
        var active = state.getPlans().get(state.getActivePlanId());
        var quote = active == null ? null : calculator.calculate(state, active);
        List<SavedPlan.PlanStep> taskPlan =
                saved != null
                        ? saved.steps()
                        : List.of(
                                new SavedPlan.PlanStep(
                                        "assets",
                                        "检索活动素材",
                                        List.of(),
                                        state.getAssets().isEmpty()
                                                ? status(state, ToolName.SEARCH_ASSETS)
                                                : "SUCCEEDED"),
                                new SavedPlan.PlanStep(
                                        "script",
                                        "生成活动脚本",
                                        List.of("assets"),
                                        active != null
                                                        && state.getScripts()
                                                                .containsKey(active.planId())
                                                ? "SUCCEEDED"
                                                : status(state, ToolName.GENERATE_SCRIPT)),
                                new SavedPlan.PlanStep(
                                        "save",
                                        "核对并保存方案",
                                        List.of("script"),
                                        status(state, ToolName.SAVE_PLAN)));
        return new PlanResultVO(
                state.getRequest().requestId(),
                state.getStatus(),
                state.getReasonCode(),
                PlanResultMessage.render(state, quote, saved),
                List.copyOf(state.getMissingFields()),
                state.getModelMode(),
                state.getModelIdentity(),
                "LOCAL_SYNTHETIC_TOOLS",
                state.getDataVersion(),
                state.getRequest().budgetUnits(),
                state.spentUnits(),
                saved != null ? BigDecimal.ZERO : quote == null ? null : quote.remainingUnits(),
                saved != null
                        ? state.spentUnits()
                        : quote == null ? null : quote.projectedTotalUnits(),
                state.getRequest().deadlineTime(),
                saved != null
                        ? saved.estimatedFinishTime()
                        : quote == null ? null : quote.estimatedFinishTime(),
                state.getElapsedMinutes(),
                state.getTotalModelSteps(),
                saved != null,
                saved,
                taskPlan,
                List.copyOf(state.getPlans().values()),
                List.copyOf(state.getQuotes().values()),
                List.copyOf(state.getAttempts()),
                replayed,
                resumed);
    }

    private String status(AgentState state, ToolName tool) {
        return state.getAttempts().stream()
                .filter(attempt -> attempt.tool() == tool)
                .filter(
                        attempt ->
                                tool == ToolName.SEARCH_ASSETS
                                        || attempt.arguments()
                                                .path("planId")
                                                .asText()
                                                .equals(state.getActivePlanId()))
                .reduce((previous, current) -> current)
                .map(ToolAttempt::status)
                .orElse("PENDING");
    }
}
