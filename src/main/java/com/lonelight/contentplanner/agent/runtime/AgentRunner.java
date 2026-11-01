package com.lonelight.contentplanner.agent.runtime;

import com.lonelight.contentplanner.agent.client.DecisionContext;
import com.lonelight.contentplanner.agent.client.ModelClient;
import com.lonelight.contentplanner.agent.client.ModelFailure;
import com.lonelight.contentplanner.common.exception.BusinessException;
import com.lonelight.contentplanner.common.exception.InjectedSaveFailureException;
import com.lonelight.contentplanner.common.json.JsonCodec;
import com.lonelight.contentplanner.config.AgentProperties;
import com.lonelight.contentplanner.model.agent.AgentAction;
import com.lonelight.contentplanner.model.agent.AgentState;
import com.lonelight.contentplanner.model.agent.TraceEvent;
import com.lonelight.contentplanner.model.enums.TaskStatus;
import com.lonelight.contentplanner.model.enums.ToolName;
import com.lonelight.contentplanner.model.plan.SavedPlan;
import com.lonelight.contentplanner.model.tool.ToolAttempt;
import com.lonelight.contentplanner.model.tool.ToolObservation;
import com.lonelight.contentplanner.service.TaskPersistenceService;
import com.lonelight.contentplanner.tool.PlanCalculator;
import com.lonelight.contentplanner.tool.ToolExecutor;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class AgentRunner {
    private final ModelClient model;
    private final ToolExecutor tools;
    private final PlanCalculator calculator;
    private final TaskPersistenceService store;
    private final JsonCodec json;
    private final AgentProperties properties;
    private final Clock clock;

    public AgentRunner(
            ModelClient model,
            ToolExecutor tools,
            PlanCalculator calculator,
            TaskPersistenceService store,
            JsonCodec json,
            AgentProperties properties,
            Clock clock) {
        this.model = model;
        this.tools = tools;
        this.calculator = calculator;
        this.store = store;
        this.json = json;
        this.properties = properties;
        this.clock = clock;
    }

    public AgentState run(AgentState state, boolean resumed) {
        state.setCurrentRunId(UUID.randomUUID().toString());
        state.setStatus(TaskStatus.RUNNING);
        state.setReasonCode(null);
        state.setMessage(null);
        event(
                state,
                0,
                "RUN_STARTED",
                Map.of(
                        "maxSteps", properties.getMaxSteps(),
                        "modelIdentity", model.identity(),
                        "resumed", resumed));
        for (int step = 1; step <= properties.getMaxSteps(); step++) {
            state.setTotalModelSteps(state.getTotalModelSteps() + 1);
            var active = state.getPlans().get(state.getActivePlanId());
            var assessment =
                    active == null || state.getSavedPlan() != null
                            ? null
                            : DecisionContext.CurrentAssessment.from(
                                    calculator.calculate(state, active));
            event(
                    state,
                    step,
                    "MODEL_REQUESTED",
                    Map.of(
                            "totalModelSteps",
                            state.getTotalModelSteps(),
                            "currentAssessment",
                            assessment == null ? Map.of() : assessment));
            store.checkpoint(state);
            ModelClient.ModelReply reply;
            try {
                reply =
                        model.decide(
                                new DecisionContext(
                                        json.copy(state, AgentState.class),
                                        step,
                                        properties.getMaxSteps(),
                                        calculator.businessTime(state),
                                        resumed,
                                        assessment));
                validate(reply == null ? null : reply.action());
            } catch (ModelFailure error) {
                state.setLastObservation(
                        ToolObservation.error(
                                error.getCode(), error.getMessage(), error.isRecoverable()));
                event(
                        state,
                        step,
                        "MODEL_ERROR",
                        Map.of("code", error.getCode(), "message", error.getMessage()));
                if (!error.isRecoverable()) return stop(state, error.getCode(), error.getMessage());
                store.checkpoint(state);
                continue;
            } catch (RuntimeException error) {
                log.error("模型适配发生非预期异常", error);
                return stop(state, "MODEL_INTERNAL_ERROR", "模型适配异常；已成功步骤保留，未切换运行模式");
            }
            AgentAction action = reply.action();
            event(
                    state,
                    step,
                    "MODEL_ACTION",
                    Map.of(
                            "action",
                            action,
                            "metadata",
                            reply.metadata() == null ? json.tree(Map.of()) : reply.metadata()));
            if (action.type() == AgentAction.Type.FINISH) {
                String invalid =
                        action.status() == TaskStatus.COMPLETED ? completionProblem(state) : null;
                if (invalid != null) {
                    state.setLastObservation(
                            ToolObservation.error("INVALID_COMPLETION", invalid, false));
                    event(state, step, "ACTION_REJECTED", state.getLastObservation());
                    store.checkpoint(state);
                    continue;
                }
                state.setStatus(action.status());
                state.setReasonCode(
                        action.status() == TaskStatus.COMPLETED
                                ? "OK"
                                : reasonCode(state, action.status()));
                state.setMessage(action.reason());
                state.setMissingFields(
                        action.missingFields() == null
                                ? List.of()
                                : List.copyOf(action.missingFields()));
                event(
                        state,
                        step,
                        "FINISHED",
                        Map.of("status", state.getStatus(), "reasonCode", state.getReasonCode()));
                store.checkpoint(state);
                return state;
            }
            state = execute(state, step, action);
        }
        return stop(state, "STEP_LIMIT", "达到本次运行模型决策次数上限，任务未完成；成功步骤及实际保存状态已保留");
    }

    private AgentState execute(AgentState original, int step, AgentAction action) {
        AgentState candidate = json.copy(original, AgentState.class);
        ToolExecutor.Execution execution;
        try {
            execution = tools.execute(action, candidate);
        } catch (RuntimeException error) {
            log.error("工具 {} 发生非预期异常", action.tool(), error);
            execution =
                    new ToolExecutor.Execution(
                            ToolObservation.error("TOOL_INTERNAL_ERROR", "工具内部异常，未确认成功", false),
                            null,
                            false);
        }
        recordAttempt(candidate, step, action, execution.observation());
        try {
            store.commit(candidate, execution.newPlan(), execution.failSaveAfterInsert());
            return candidate;
        } catch (InjectedSaveFailureException error) {
            original.getInjectedFailures().merge(ToolName.SAVE_PLAN, 1, Integer::sum);
            recordAttempt(original, step, action, tools.saveFailure());
            store.checkpoint(original);
            return original;
        } catch (DataAccessException error) {
            log.error("工具结果检查点写入失败，请求 {}", original.getRequest().requestId(), error);
            throw new BusinessException(
                    503, "CHECKPOINT_UNAVAILABLE", "数据库提交失败，不能确认本次动作或方案已保存；请查询已有持久化状态");
        }
    }

    private void recordAttempt(
            AgentState state, int step, AgentAction action, ToolObservation observation) {
        state.getAttempts()
                .add(
                        new ToolAttempt(
                                state.getCurrentRunId(),
                                step,
                                action.tool(),
                                action.arguments(),
                                observation.success() ? "SUCCEEDED" : "FAILED",
                                observation));
        state.setElapsedMinutes(
                Math.addExact(state.getElapsedMinutes(), observation.durationMinutes()));
        state.setLastObservation(observation);
        event(
                state,
                step,
                "TOOL_OBSERVATION",
                Map.of(
                        "tool",
                        action.tool(),
                        "arguments",
                        action.arguments(),
                        "result",
                        observation,
                        "spentUnits",
                        state.spentUnits(),
                        "elapsedMinutes",
                        state.getElapsedMinutes()));
    }

    public String completionProblem(AgentState state) {
        var saved = store.savedPlan(state.getModelMode(), state.getRequest().requestId());
        return completionProblem(state, saved.orElse(null));
    }

    public String completionProblem(AgentState state, SavedPlan plan) {
        if (plan == null || state.getSavedPlan() == null) return "方案尚未真实保存，请继续工具执行或明确返回未完成";
        if (!plan.savedPlanId().equals(state.getSavedPlan().savedPlanId())
                || !state.getScripts().containsKey(plan.plan().planId())) return "保存记录与脚本产物不一致";
        if (state.spentUnits().compareTo(plan.totalCostUnits()) != 0) return "费用账目与保存记录不一致";
        if (state.spentUnits().compareTo(state.getRequest().budgetUnits()) > 0) return "实际费用超过预算";
        if (plan.estimatedFinishTime().isAfter(state.getRequest().deadlineTime()))
            return "保存方案不能在截止时间前完成";
        if (!plan.plan().assetIds().stream().allMatch(state.getAssets()::containsKey))
            return "保存方案引用了未取得的素材";
        return null;
    }

    private void validate(AgentAction action) {
        if (action == null
                || action.type() == null
                || action.reason() == null
                || action.reason().isBlank()
                || action.reason().length() > 600)
            throw new ModelFailure(
                    "INVALID_MODEL_ACTION", "动作必须包含 type 和不超过 600 字符的简短 reason", true);
        if (action.type() == AgentAction.Type.TOOL_CALL
                && (action.tool() == null
                        || action.arguments() == null
                        || !action.arguments().isObject())) {
            throw new ModelFailure("INVALID_MODEL_ACTION", "工具动作必须包含合法 tool 和 arguments 对象", true);
        }
        if (action.type() == AgentAction.Type.FINISH
                && (action.status() == null || action.status() == TaskStatus.RUNNING)) {
            throw new ModelFailure("INVALID_MODEL_ACTION", "结束动作必须指定合法终态", true);
        }
        if (action.missingFields() != null
                && (action.missingFields().size() > 20
                        || action.missingFields().stream()
                                .anyMatch(
                                        field ->
                                                field == null
                                                        || field.isBlank()
                                                        || field.length() > 100))) {
            throw new ModelFailure(
                    "INVALID_MODEL_ACTION", "missingFields 最多包含 20 个非空字符串，每项不超过 100 字符", true);
        }
    }

    private String reasonCode(AgentState state, TaskStatus status) {
        if (status == TaskStatus.NEEDS_INPUT) return "MATERIALS_NEEDED";
        if (state.getLastObservation() != null && !state.getLastObservation().success())
            return state.getLastObservation().code();
        if (!state.getPlans().isEmpty()
                && state.getPlans().values().stream()
                        .noneMatch(plan -> calculator.calculate(state, plan).feasible()))
            return "NO_FEASIBLE_PLAN";
        return "MODEL_STOPPED";
    }

    private AgentState stop(AgentState state, String code, String message) {
        state.setStatus(TaskStatus.INCOMPLETE);
        state.setReasonCode(code);
        state.setMessage(message);
        event(state, 0, "FINISHED", Map.of("status", TaskStatus.INCOMPLETE, "reasonCode", code));
        store.checkpoint(state);
        return state;
    }

    public void event(AgentState state, int step, String type, Object detail) {
        state.getTrace()
                .add(
                        new TraceEvent(
                                state.getTrace().size() + 1L,
                                state.getCurrentRunId(),
                                step,
                                OffsetDateTime.now(clock),
                                state.getModelMode(),
                                type,
                                json.tree(detail)));
    }
}
