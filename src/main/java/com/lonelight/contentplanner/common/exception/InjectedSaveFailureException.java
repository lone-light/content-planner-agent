package com.lonelight.contentplanner.common.exception;

/** 保存 INSERT 后触发的验收故障，必须让整个保存事务回滚。 */
public class InjectedSaveFailureException extends RuntimeException {
    public InjectedSaveFailureException() {
        super("保存事务故障注入");
    }
}
