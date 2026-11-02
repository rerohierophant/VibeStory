package com.vibestory.provider;

import java.util.Optional;

public interface VideoProvider {
    record Capabilities(boolean querySupported, boolean idempotencyKeySupported, int durationMs) {}
    record Request(long userId, long projectId, long episodeId, long taskId, int attempt,
                   String signature, String inputSnapshot) {}
    record Result(String storageKey, int durationMs) {}
    Capabilities capabilities();
    Result generate(Request request) throws InterruptedException;
    default Optional<Result> poll(String externalId) { return Optional.empty(); }
}
