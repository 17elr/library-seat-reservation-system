package cn.edu.library.web;

import cn.edu.library.service.BusinessException;
import jakarta.validation.ConstraintViolationException;
import java.util.Map;
import org.slf4j.*;
import org.springframework.dao.*;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ApiErrors {

    private static final Logger log = LoggerFactory.getLogger(ApiErrors.class);

    @ExceptionHandler(BusinessException.class)
    ResponseEntity<?> business(BusinessException e) {
        return ResponseEntity.status(e.status).body(
            Map.of("message", e.getMessage())
        );
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<?> invalid(MethodArgumentNotValidException e) {
        var errors = e.getBindingResult().getFieldErrors();
        String field = errors.isEmpty() ? "参数" : errors.getFirst().getField();
        return ResponseEntity.badRequest().body(
            Map.of(
                "message",
                "请检查输入：" + field + " 的值不合法或未填写",
                "errors",
                errors
                    .stream()
                    .collect(
                        java.util.stream.Collectors.toMap(
                            e2 -> e2.getField(),
                            e2 ->
                                java.util.Objects.requireNonNullElse(
                                    e2.getDefaultMessage(),
                                    "输入不合法"
                                ),
                            (a, b) -> a
                        )
                    )
            )
        );
    }

    @ExceptionHandler({
        ConstraintViolationException.class,
        org.springframework.web.bind.MissingServletRequestParameterException.class,
        HttpMessageNotReadableException.class,
        MethodArgumentTypeMismatchException.class,
    })
    ResponseEntity<?> format(Exception e) {
        return ResponseEntity.badRequest().body(
            Map.of("message", "输入格式不正确，请检查日期、时间和必填项")
        );
    }

    @ExceptionHandler(DuplicateKeyException.class)
    ResponseEntity<?> duplicate(Exception e) {
        return ResponseEntity.status(409).body(
            Map.of("message", "编号或网格位置已经存在，请使用其他值")
        );
    }

    @ExceptionHandler({
        CannotAcquireLockException.class,
        QueryTimeoutException.class,
    })
    ResponseEntity<?> busy(Exception e) {
        return ResponseEntity.status(409).body(
            Map.of("message", "系统正在处理其他请求，请稍后重试")
        );
    }

    @ExceptionHandler(
        org.springframework.web.servlet.resource.NoResourceFoundException.class
    )
    ResponseEntity<?> missing(Exception e) {
        return ResponseEntity.status(404).body(Map.of("message", "资源不存在"));
    }

    @ExceptionHandler({
        org.springframework.dao.DataAccessResourceFailureException.class,
        org.springframework.transaction.CannotCreateTransactionException.class,
    })
    ResponseEntity<?> unavailable(Exception e) {
        log.warn("数据库暂不可用", e);
        return ResponseEntity.status(503).body(
            Map.of("message", "服务暂不可用，请稍后重试")
        );
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<?> server(Exception e) {
        log.error("API 操作失败", e);
        return ResponseEntity.status(500).body(
            Map.of("message", "操作未完成，请稍后重试；如重复发生请联系管理员")
        );
    }
}
