package com.mediflow.agent.api;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {
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
