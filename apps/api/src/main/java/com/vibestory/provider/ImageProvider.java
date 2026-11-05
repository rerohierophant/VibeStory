package com.vibestory.provider;

import java.util.List;

public interface ImageProvider {
    record Capabilities(boolean querySupported, boolean idempotencyKeySupported, int characterReferences,
                        boolean sceneReferences, int maxPromptCharacters) {}
    record AssetSnapshot(String assetId, String name, String versionId, String versionLabel) {}
    record Sources(List<AssetSnapshot> characters, AssetSnapshot scene) {}
    record Reference(String versionId, String role, String storageKey, String mediaType) {}
    record Guidance(String mode, List<String> usedVersionIds, String notice) {}
    record PreferenceUse(String id, int version, String scope, String rule) {}
    record RecipeUse(String id, String versionId, int version, String name, String template,
                     java.util.Map<String, String> parameters, String rendered) {}
    record Learning(String basePrompt, List<PreferenceUse> preferences, RecipeUse recipe,
                    String sourceTaskId, String feedbackId) {}
    record Input(String type, long targetId, String targetType, String provider, String endpoint, String model,
                 String prompt, String aspectRatio, String promptVersion, Sources sources,
                 List<Reference> references, Guidance guidance, String regenerateNonce,
                 @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) Learning learning) {
        public Input(String type, long targetId, String targetType, String provider, String endpoint, String model,
                     String prompt, String aspectRatio, String promptVersion, Sources sources,
                     List<Reference> references, Guidance guidance, String regenerateNonce) {
            this(type, targetId, targetType, provider, endpoint, model, prompt, aspectRatio, promptVersion, sources,
                references, guidance, regenerateNonce, null);
        }
    }
    record Result(byte[] bytes, String mediaType, String actualModel, String diagnostics) {}
    Result generate(Input input, String signature) throws InterruptedException;
}
