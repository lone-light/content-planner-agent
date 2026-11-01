package com.lonelight.contentplanner.common.web;

import com.lonelight.contentplanner.common.exception.BusinessException;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<Result<Void>> database(DataAccessException error) {
        log.error("数据库访问失败", error);
        return ResponseEntity.status(503)
                .cacheControl(CacheControl.noStore())
                .body(new Result<>(503, "数据库不可用，不能确认本次操作已持久化", null));
    }

    @ExceptionHandler(BusinessException.class)
    ResponseEntity<Result<Map<String, String>>> business(BusinessException error) {
        return ResponseEntity.status(error.getStatus())
                .cacheControl(CacheControl.noStore())
                .body(
                        new Result<>(
                                error.getStatus(),
                                error.getMessage(),
                                Map.of("errorCode", error.getCode())));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Result<Void>> validation(MethodArgumentNotValidException error) {
        var field = error.getBindingResult().getFieldError();
        return ResponseEntity.badRequest()
                .cacheControl(CacheControl.noStore())
                .body(new Result<>(400, field == null ? "参数无效" : field.getDefaultMessage(), null));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Result<Void>> json() {
        return ResponseEntity.badRequest()
                .cacheControl(CacheControl.noStore())
                .body(new Result<>(400, "JSON、字段类型或时间格式无效；时间必须包含时区", null));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Result<Void>> unexpected(Exception error) {
        if (error instanceof ErrorResponse response
                && response.getStatusCode().is4xxClientError()) {
            int status = response.getStatusCode().value();
            return ResponseEntity.status(response.getStatusCode())
                    .headers(response.getHeaders())
                    .cacheControl(CacheControl.noStore())
                    .body(new Result<>(status, status == 404 ? "资源不存在" : "请求方法或参数无效", null));
        }
        log.error("请求处理失败", error);
        return ResponseEntity.internalServerError()
                .cacheControl(CacheControl.noStore())
                .body(new Result<>(500, "系统异常，未确认的结果不能视为已保存", null));
    }
}
