package com.lonelight.contentplanner.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.lonelight.contentplanner.model.entity.AgentTaskEntity;
import java.time.LocalDateTime;
import java.util.Optional;

/** 任务记录的 MP 服务契约，查询和更新均按任务主键执行。 */
public interface AgentTaskService extends IService<AgentTaskEntity> {
    Optional<AgentTaskEntity> findTask(String mode, String requestId);

    void updateState(String mode, String requestId, String stateJson, LocalDateTime updateTime);
}
