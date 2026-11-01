package com.lonelight.contentplanner.service;

import com.lonelight.contentplanner.model.dto.PlanRequest;
import com.lonelight.contentplanner.model.vo.PlanResultVO;

/** 内容方案的提交、查询、显式续跑与执行记录契约。 */
public interface PlanService {
    PlanResultVO submit(PlanRequest request);

    PlanResultVO get(String requestId);

    PlanResultVO resume(String requestId);

    String trace(String requestId);
}
