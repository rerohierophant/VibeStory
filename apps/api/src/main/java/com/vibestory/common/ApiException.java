package com.vibestory.common;

import org.springframework.http.HttpStatus;

public class ApiException extends RuntimeException {
    private final HttpStatus status;

    public ApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus status() { return status; }
    public static ApiException notFound() { return new ApiException(HttpStatus.NOT_FOUND, "资源不存在"); }
    public static ApiException conflict(String message) { return new ApiException(HttpStatus.CONFLICT, message); }
    public static ApiException badRequest(String message) { return new ApiException(HttpStatus.BAD_REQUEST, message); }
}
