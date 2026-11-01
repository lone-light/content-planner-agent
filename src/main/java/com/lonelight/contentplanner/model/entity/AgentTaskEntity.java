package com.lonelight.contentplanner.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("agent_task")
public class AgentTaskEntity {
    @TableId(type = IdType.INPUT)
    private String id;

    private String modelMode;
    private String requestId;
    private String payloadHash;
    private String stateJson;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
