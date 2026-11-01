package com.lonelight.contentplanner.service.impl;

import com.lonelight.contentplanner.common.exception.BusinessException;
import com.lonelight.contentplanner.common.exception.InjectedSaveFailureException;
import com.lonelight.contentplanner.common.json.JsonCodec;
import com.lonelight.contentplanner.model.agent.AgentState;
import com.lonelight.contentplanner.model.agent.StoredTask;
import com.lonelight.contentplanner.model.entity.AgentTaskEntity;
import com.lonelight.contentplanner.model.entity.SavedPlanEntity;
import com.lonelight.contentplanner.model.plan.SavedPlan;
import com.lonelight.contentplanner.service.AgentTaskService;
import com.lonelight.contentplanner.service.SavedPlanService;
import com.lonelight.contentplanner.service.TaskPersistenceService;
import com.lonelight.contentplanner.service.support.TaskKey;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Coordinates short transactions; CRUD is implemented by MyBatis-Plus services. */
@Service
public class TaskPersistenceServiceImpl implements TaskPersistenceService {
    private final AgentTaskService tasks;
    private final SavedPlanService plans;
    private final JsonCodec json;
    private final Clock clock;
    private final TransactionTemplate transactions;

    public TaskPersistenceServiceImpl(
            AgentTaskService tasks,
            SavedPlanService plans,
            JsonCodec json,
            Clock clock,
            PlatformTransactionManager manager) {
        this.tasks = tasks;
        this.plans = plans;
        this.json = json;
        this.clock = clock;
        transactions = new TransactionTemplate(manager);
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public Optional<StoredTask> find(String mode, String requestId) {
        return tasks.findTask(mode, requestId)
                .map(
                        row ->
                                new StoredTask(
                                        row.getPayloadHash(),
                                        json.read(row.getStateJson(), AgentState.class)));
    }

    @Override
    public void create(AgentState state, String payloadHash) {
        transactions.executeWithoutResult(
                status -> {
                    var row = new AgentTaskEntity();
                    row.setId(TaskKey.of(state.getModelMode(), state.getRequest().requestId()));
                    row.setModelMode(state.getModelMode());
                    row.setRequestId(state.getRequest().requestId());
                    row.setPayloadHash(payloadHash);
                    row.setStateJson(json.write(state));
                    row.setCreateTime(currentTime());
                    row.setUpdateTime(row.getCreateTime());
                    if (!tasks.save(row)) throw new IllegalStateException("任务创建失败");
                });
    }

    @Override
    public void checkpoint(AgentState state) {
        commit(state, null, false);
    }

    @Override
    public void commit(AgentState state, SavedPlan newPlan, boolean failAfterInsert) {
        if (newPlan != null
                && (!Objects.equals(newPlan.requestId(), state.getRequest().requestId())
                        || !Objects.equals(newPlan.modelMode(), state.getModelMode()))) {
            throw new BusinessException(409, "PLAN_OWNERSHIP_CONFLICT", "保存方案必须关联当前任务和模型模式");
        }
        transactions.executeWithoutResult(
                status -> {
                    // Updating the parent first verifies the logical relation and locks it until
                    // commit.
                    tasks.updateState(
                            state.getModelMode(),
                            state.getRequest().requestId(),
                            json.write(state),
                            currentTime());
                    if (newPlan != null) {
                        var existing =
                                savedPlan(state.getModelMode(), state.getRequest().requestId());
                        if (existing.isPresent()
                                && !existing.get()
                                        .plan()
                                        .planId()
                                        .equals(newPlan.plan().planId())) {
                            throw new BusinessException(409, "PLAN_CONFLICT", "该请求已有不同方案，不能覆盖");
                        }
                        if (existing.isEmpty()) {
                            var row = new SavedPlanEntity();
                            row.setId(newPlan.savedPlanId());
                            row.setModelMode(state.getModelMode());
                            row.setRequestId(state.getRequest().requestId());
                            row.setPlanId(newPlan.plan().planId());
                            row.setPlanJson(json.write(newPlan));
                            row.setSaveTime(
                                    newPlan.saveTime()
                                            .withOffsetSameInstant(ZoneOffset.UTC)
                                            .toLocalDateTime());
                            if (!plans.save(row)) throw new IllegalStateException("方案保存失败");
                        }
                        if (failAfterInsert) throw new InjectedSaveFailureException();
                    }
                });
    }

    @Override
    public Optional<SavedPlan> savedPlan(String mode, String requestId) {
        return plans.findPlan(mode, requestId)
                .map(row -> json.read(row.getPlanJson(), SavedPlan.class));
    }

    private LocalDateTime currentTime() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
