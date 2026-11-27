package com.mediflow.agent.controller.support;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<Map<String, String>> responseStatus(ResponseStatusException exception) {
        return ResponseEntity.status(exception.getStatusCode()).body(Map.of("error",
                exception.getReason() == null ? "请求无法处理。" : exception.getReason()));
    }
    @ExceptionHandler(OptimisticLockingFailureException.class)
    ResponseEntity<Map<String, String>> changed() {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "预约任务已更新，请刷新后再试。"));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, String>> validation(MethodArgumentNotValidException exception) {
        return ResponseEntity.badRequest().body(Map.of("error", "请求参数无效"));
    }

    @ExceptionHandler({IllegalArgumentException.class, SecurityException.class})
    ResponseEntity<Map<String, String>> clientError(RuntimeException exception) {
        HttpStatus status = exception instanceof SecurityException
                ? HttpStatus.FORBIDDEN : HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status).body(Map.of("error", exception.getMessage()));
    }
}
