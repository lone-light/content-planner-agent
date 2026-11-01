package com.lonelight.contentplanner.service;

import com.lonelight.contentplanner.model.agent.AgentState;
import com.lonelight.contentplanner.model.agent.StoredTask;
import com.lonelight.contentplanner.model.plan.SavedPlan;
import java.util.Optional;

/** 任务和方案的事务持久化契约。网络模型调用不包含在持久化事务中。 */
public interface TaskPersistenceService {
    Optional<StoredTask> find(String mode, String requestId);

    void create(AgentState state, String payloadHash);

    void checkpoint(AgentState state);

    void commit(AgentState state, SavedPlan newPlan, boolean failAfterInsert);

    Optional<SavedPlan> savedPlan(String mode, String requestId);
}
