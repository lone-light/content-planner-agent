package com.lonelight.contentplanner.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.lonelight.contentplanner.model.entity.SavedPlanEntity;
import java.util.Optional;

/** 已保存方案的 MP 服务契约，模型模式与请求 ID 构成唯一业务键。 */
public interface SavedPlanService extends IService<SavedPlanEntity> {
    Optional<SavedPlanEntity> findPlan(String mode, String requestId);
}
