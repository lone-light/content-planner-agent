package com.lonelight.contentplanner.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("saved_plan")
public class SavedPlanEntity {
    @TableId(type = IdType.INPUT)
    private String id;

    private String modelMode;
    private String requestId;
    private String planId;
    private String planJson;
    private LocalDateTime saveTime;
}
