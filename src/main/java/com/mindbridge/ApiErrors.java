package com.mindbridge;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Avoid logging rejected wellbeing message content in default validation warnings. */
@RestControllerAdvice
public class ApiErrors {
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String,String>> validation(MethodArgumentNotValidException error) {
        return ResponseEntity.badRequest().body(Map.of("error","Invalid input. Check required fields and length limits."));
    }
}
