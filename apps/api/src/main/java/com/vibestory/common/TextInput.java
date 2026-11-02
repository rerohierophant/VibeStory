package com.vibestory.common;

import java.nio.charset.StandardCharsets;

public final class TextInput {
    private TextInput() {}

    public static void validate(String text, int maxBytes, String label) {
        if (text == null || text.indexOf('\0') >= 0 || !StandardCharsets.UTF_8.newEncoder().canEncode(text))
            throw ApiException.badRequest(label + "必须是有效 Unicode 文本，不能包含 NUL");
        if (text.getBytes(StandardCharsets.UTF_8).length > maxBytes)
            throw ApiException.badRequest(label + "超过允许的 UTF-8 字节数 " + maxBytes);
    }

    public static String name(String text, String label) {
        validate(text, 480, label);
        if (text.isBlank() || text.length() > 120) throw ApiException.badRequest(label + "须为 1 至 120 字符");
        return text.strip();
    }
}
