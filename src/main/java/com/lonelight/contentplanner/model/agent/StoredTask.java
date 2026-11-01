package com.lonelight.contentplanner.model.agent;

/** 读取任务时同时返回输入指纹和状态，供幂等及恢复校验使用。 */
public record StoredTask(String payloadHash, AgentState state) {}
