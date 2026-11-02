package com.vibestory.provider;

import java.util.List;

public interface TextProvider {
    record Capabilities(boolean querySupported, boolean idempotencyKeySupported) {}
    Capabilities capabilities();
    record SceneOption(String name, List<String> keywords) {}
    record Input(String type, long targetId, String provider, String endpoint, String model,
                 String promptVersion, long storyRevision, long shotRevision, String story,
                 List<SceneOption> sceneOptions, int maxTokens, boolean disableThinking) {}
    record ShotDraft(String description, String dialogue, String sceneName, String shotSize,
                     String cameraMove, int durationMs, List<String> characterNames) {}
    record Result(List<ShotDraft> shots, String rawResponse, String actualModel) {}
    Result generate(Input input) throws InterruptedException;
}
