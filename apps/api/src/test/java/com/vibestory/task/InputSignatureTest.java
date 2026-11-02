package com.vibestory.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class InputSignatureTest {
    private final InputSignature signatures = new InputSignature(new ObjectMapper());

    @Test void whitespaceAndLineEndingsAreNormalized() {
        assertThat(hash(Map.of("prompt", "  雨夜\r\n\t街头　  重逢 ")))
            .isEqualTo(hash(Map.of("prompt", "雨夜 街头 重逢")));
    }

    @Test void nestedKeysAndAssetVersionSetsAreCanonical() {
        var first = new LinkedHashMap<String, Object>();
        first.put("prompt", "hello");
        first.put("params", Map.of("b", 2, "a", 1));
        first.put("assetVersions", List.of(Map.of("versionId", 9), Map.of("versionId", 3)));
        assertThat(hash(first)).isEqualTo(hash(Map.of("params", Map.of("a", 1, "b", 2), "prompt", "hello",
            "assetVersions", List.of(Map.of("versionId", 3), Map.of("versionId", 9)))));
    }

    @Test void floatsUseSixDecimalPlacesButIntegerIdsAreNeverRounded() throws Exception {
        assertThat(hash(Map.of("scale", 0.1 + 0.2))).isEqualTo(hash(Map.of("scale", new BigDecimal("0.300000"))));
        assertThat(hash(Map.of("scale", 1.0))).isEqualTo(hash(Map.of("scale", 1)));
        assertThat(hash(Map.of("id", 123456789012345678L))).isNotEqualTo(hash(Map.of("id", 123456789012345679L)));
        var snapshot = signatures.of(Map.of("id", 123456789012345670L));
        assertThat(new ObjectMapper().readTree(snapshot.json()).get("id").longValue()).isEqualTo(123456789012345670L);
    }

    @Test void nonceIsOpaqueAndChangesIdentity() {
        assertThat(hash(Map.of("regenerateNonce", "first"))).isNotEqualTo(hash(Map.of("regenerateNonce", "second")));
        assertThat(hash(Map.of("regenerateNonce", "first"))).isNotEqualTo(hash(Map.of("regenerateNonce", " first ")));
    }

    @Test void orderedArraysAndSourceVersionsRetainMeaning() {
        assertThat(hash(Map.of("frames", List.of(1, 2)))).isNotEqualTo(hash(Map.of("frames", List.of(2, 1))));
        assertThat(hash(Map.of("sourceImageVersion", 1))).isNotEqualTo(hash(Map.of("sourceImageVersion", 2)));
    }

    @Test void storyWhitespaceAndParagraphsArePreservedInSignatureAndExecutionSnapshot() throws Exception {
        var original = "  雨夜\r\n\n便利店  ";
        var snapshot = signatures.of(Map.of("story", original));
        assertThat(new ObjectMapper().readTree(snapshot.json()).get("story").asText()).isEqualTo(original);
        assertThat(snapshot.signature()).isNotEqualTo(hash(Map.of("story", "雨夜 便利店")));
    }

    private String hash(Object input) { return signatures.of(input).signature(); }
}
