package com.vibestory.provider;

import java.util.List;

public interface ImageProvider {
    record Capabilities(boolean querySupported, boolean idempotencyKeySupported, int characterReferences,
                        boolean sceneReferences, int maxPromptCharacters) {}
    record AssetSnapshot(String assetId, String name, String versionId, String versionLabel) {}
    record Sources(List<AssetSnapshot> characters, AssetSnapshot scene) {}
    record Reference(String versionId, String role, String storageKey, String mediaType) {}
    record Guidance(String mode, List<String> usedVersionIds, String notice) {}
    record Input(String type, long targetId, String targetType, String provider, String endpoint, String model,
                 String prompt, String aspectRatio, String promptVersion, Sources sources,
                 List<Reference> references, Guidance guidance, String regenerateNonce) {}
    record Result(byte[] bytes, String mediaType, String actualModel, String diagnostics) {}
    Result generate(Input input, String signature) throws InterruptedException;
}
