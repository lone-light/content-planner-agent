package com.lonelight.contentplanner.service.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.lonelight.contentplanner.mapper.AgentTaskMapper;
import com.lonelight.contentplanner.model.entity.AgentTaskEntity;
import com.lonelight.contentplanner.service.AgentTaskService;
import com.lonelight.contentplanner.service.support.TaskKey;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class AgentTaskServiceImpl extends ServiceImpl<AgentTaskMapper, AgentTaskEntity>
        implements AgentTaskService {
    @Override
    public Optional<AgentTaskEntity> findTask(String mode, String requestId) {
        return Optional.ofNullable(getById(TaskKey.of(mode, requestId)));
    }

    @Override
    public void updateState(
            String mode, String requestId, String stateJson, LocalDateTime updateTime) {
        boolean changed =
                lambdaUpdate()
                        .eq(AgentTaskEntity::getId, TaskKey.of(mode, requestId))
                        .set(AgentTaskEntity::getStateJson, stateJson)
                        .set(AgentTaskEntity::getUpdateTime, updateTime)
                        .update();
        if (!changed) throw new IllegalStateException("检查点目标不存在");
    }
}
