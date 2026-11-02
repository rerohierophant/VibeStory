package com.vibestory.task;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

@Component
public class InputSignature {
    private final ObjectMapper json;
    public InputSignature(ObjectMapper json) { this.json = json; }
    public record Snapshot(String signature, String json) {}

    public Snapshot of(Object input) {
        try {
            String canonical = json.writeValueAsString(normalize(json.valueToTree(input), ""));
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(canonical.getBytes(StandardCharsets.UTF_8)));
            return new Snapshot(digest, canonical);
        } catch (JsonProcessingException | NoSuchAlgorithmException error) {
            throw new IllegalStateException("无法规范化生成输入", error);
        }
    }

    private JsonNode normalize(JsonNode node, String key) {
        if (node.isObject()) {
            var result = JsonNodeFactory.instance.objectNode();
            var fields = new ArrayList<String>();
            node.fieldNames().forEachRemaining(fields::add);
            fields.stream().sorted().forEach(name -> result.set(name, normalize(node.get(name), name)));
            return result;
        }
        if (node.isArray()) {
            var values = new ArrayList<JsonNode>();
            node.forEach(value -> values.add(normalize(value, "")));
            // Reference sets are unordered; other arrays retain their meaning and order.
            if (key.equals("assetVersions")) values.sort(Comparator.comparing(JsonNode::toString));
            var result = JsonNodeFactory.instance.arrayNode();
            values.forEach(result::add);
            return result;
        }
        if (node.isTextual() && !key.equals("regenerateNonce") && !key.equals("story")) {
            return TextNode.valueOf(node.textValue().replaceAll("[\\s\\p{Z}]+", " ").strip());
        }
        if (node.isNumber()) {
            // Generation parameters use six fractional digits; integer IDs keep full precision.
            if (node.isIntegralNumber()) return BigIntegerNode.valueOf(node.bigIntegerValue());
            BigDecimal decimal = node.decimalValue().setScale(6, RoundingMode.HALF_UP).stripTrailingZeros();
            return decimal.scale() <= 0 ? BigIntegerNode.valueOf(decimal.toBigIntegerExact()) : DecimalNode.valueOf(decimal);
        }
        return node;
    }
}
