package com.vibestory.provider;

public class ProviderException extends RuntimeException {
    private final String code;
    private final boolean retryable;
    private final boolean unknown;
    private final String rawResponse;
    private final String actualModel;

    public ProviderException(String code, String message, boolean retryable, boolean unknown) {
        this(code, message, retryable, unknown, null, null);
    }
    public ProviderException(String code, String message, boolean retryable, boolean unknown, String rawResponse, String actualModel) {
        super(message);
        this.code = code;
        this.retryable = retryable;
        this.unknown = unknown;
        this.rawResponse = rawResponse;
        this.actualModel = actualModel;
    }
    public String code() { return code; }
    public boolean retryable() { return retryable; }
    public boolean unknown() { return unknown; }
    public String rawResponse() { return rawResponse; }
    public String actualModel() { return actualModel; }
}
