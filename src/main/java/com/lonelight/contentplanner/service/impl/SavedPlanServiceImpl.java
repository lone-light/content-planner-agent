package com.lonelight.contentplanner.service.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.lonelight.contentplanner.mapper.SavedPlanMapper;
import com.lonelight.contentplanner.model.entity.SavedPlanEntity;
import com.lonelight.contentplanner.service.SavedPlanService;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class SavedPlanServiceImpl extends ServiceImpl<SavedPlanMapper, SavedPlanEntity>
        implements SavedPlanService {
    @Override
    public Optional<SavedPlanEntity> findPlan(String mode, String requestId) {
        return lambdaQuery()
                .eq(SavedPlanEntity::getModelMode, mode)
                .eq(SavedPlanEntity::getRequestId, requestId)
                .oneOpt();
    }
}
