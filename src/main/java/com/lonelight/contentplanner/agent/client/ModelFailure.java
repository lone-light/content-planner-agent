package com.lonelight.contentplanner.agent.client;

import lombok.Getter;

@Getter
public class ModelFailure extends RuntimeException {
    private final String code;
    private final boolean recoverable;

    public ModelFailure(String code, String message, boolean recoverable) {
        super(message);
        this.code = code;
        this.recoverable = recoverable;
    }
}
