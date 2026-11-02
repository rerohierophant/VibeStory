package com.vibestory.common;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class ApiErrors extends ResponseEntityExceptionHandler {
    @ExceptionHandler(ApiException.class)
    ProblemDetail api(ApiException error, jakarta.servlet.http.HttpServletRequest request,
                      org.springframework.security.core.Authentication authentication) {
        if (error.status() == HttpStatus.NOT_FOUND && authentication != null && authentication.getPrincipal() instanceof com.vibestory.auth.Viewer viewer) {
            // Do not log raw URLs, query parameters, content or foreign owners. 404 deliberately
            // makes an absent object and a denied scope indistinguishable to callers and logs.
            var route = request.getAttribute(org.springframework.web.servlet.HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
            org.slf4j.LoggerFactory.getLogger(ApiErrors.class).atInfo()
                .addKeyValue("event", "scope_not_found")
                .addKeyValue("user_id", viewer.id())
                .addKeyValue("method", request.getMethod())
                .addKeyValue("route", route == null ? "unmatched" : route.toString())
                .log("Scoped resource absent or denied");
        }
        return ProblemDetail.forStatusAndDetail(error.status(), error.getMessage());
    }

    @ExceptionHandler(AuthenticationException.class)
    ProblemDetail authentication(AuthenticationException error) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "邮箱或密码错误");
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail conflict(DataIntegrityViolationException error) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "数据冲突，请刷新后重试");
    }

    @ExceptionHandler({ObjectOptimisticLockingFailureException.class, CannotAcquireLockException.class})
    ProblemDetail concurrentWrite(RuntimeException error) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "数据已被其他请求修改，请刷新后重试");
    }
}
