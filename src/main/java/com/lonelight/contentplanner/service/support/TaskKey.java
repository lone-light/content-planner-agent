package com.lonelight.contentplanner.service.support;

/** 模型命名空间内的任务主键。 */
public final class TaskKey {
    private TaskKey() {}

    public static String of(String modelMode, String requestId) {
        return modelMode + ":" + requestId;
    }
}
