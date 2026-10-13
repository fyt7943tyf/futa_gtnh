package com.futa_gtnh.network;

import java.nio.charset.StandardCharsets;

/** Bound network text without cutting a multi-byte code point into a replacement character. */
public final class Utf8Text {

    /** Keep the existing UTF-16 length limit without splitting a surrogate pair. */
    public static String limitLength(String value, int maxChars) {
        if (value == null || maxChars <= 0) return "";
        if (value.length() <= maxChars) return value;
        int end = maxChars;
        if (Character.isHighSurrogate(value.charAt(end - 1)) && Character.isLowSurrogate(value.charAt(end))) end--;
        return value.substring(0, end);
    }

    private Utf8Text() {}

    public static String limit(String value, int maxBytes) {
        if (value == null || maxBytes <= 0) return "";
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) return value;
        int end = maxBytes;
        while (end > 0 && (bytes[end] & 0xC0) == 0x80) end--;
        return new String(bytes, 0, end, StandardCharsets.UTF_8);
    }
}
