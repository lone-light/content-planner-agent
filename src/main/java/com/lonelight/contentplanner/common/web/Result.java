package com.lonelight.contentplanner.common.web;

public record Result<T>(int code, String message, T data) {
    public static <T> Result<T> success(T data) {
        return new Result<>(0, "success", data);
    }
}
