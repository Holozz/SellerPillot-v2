package com.sellerpilot.common;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@RestControllerAdvice
public class ApiErrors {
    private static final Logger log=LoggerFactory.getLogger(ApiErrors.class);
    @ExceptionHandler(ApiException.class)
    ResponseEntity<?> business(ApiException e) {
        if(e.status==409) log.info("event=business_conflict reason={}",e.getMessage());
        return ResponseEntity.status(e.status).body(Map.of("detail",e.detail));
    }
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<?> invalid(MethodArgumentNotValidException e) {
        return ResponseEntity.unprocessableEntity().body(Map.of("detail",e.getBindingResult().getFieldErrors().stream()
            .map(error->Map.of("field",error.getField(),"msg",error.getDefaultMessage())).toList()));
    }
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<?> constraint(DataIntegrityViolationException e) {
        Throwable cause=e.getMostSpecificCause();
        String constraint="unknown";
        java.util.regex.Matcher matcher=java.util.regex.Pattern.compile("(?i)(?:constraint|for key) ['`\"]([^'`\"]+)").matcher(String.valueOf(cause.getMessage()));
        if(matcher.find())constraint=matcher.group(1);
        log.warn("event=constraint_rejected exception={} constraint={} sql_state={}",cause.getClass().getSimpleName(),constraint,cause instanceof java.sql.SQLException sql?sql.getSQLState():"unknown");
        return ResponseEntity.status(409).body(Map.of("detail","数据违反唯一性或关联约束，本次写入已回滚"));
    }
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<?> tooLarge() {return ResponseEntity.status(413).body(Map.of("detail","文件不能超过5MiB"));}
    @ExceptionHandler(org.springframework.dao.DataAccessException.class)
    ResponseEntity<?> database(org.springframework.dao.DataAccessException e) {
        Throwable cause=e.getMostSpecificCause();
        log.error("event=database_failed exception={} sql_state={}",cause.getClass().getSimpleName(),cause instanceof java.sql.SQLException sql?sql.getSQLState():"unknown");
        return ResponseEntity.internalServerError().body(Map.of("detail","数据库操作失败，本次操作未完成"));
    }
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    ResponseEntity<?> malformed(){return ResponseEntity.unprocessableEntity().body(Map.of("detail","请求JSON包含未知字段、格式或类型错误"));}
}
