package com.github.gh2302.hbasemcp;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Translates between HBase's raw byte values and the JSON strings tools exchange.
 *
 * <p>Everything in HBase is bytes, but the overwhelming majority of real row keys, qualifiers
 * and values are UTF-8 text. So: text stays text, and anything that is not clean UTF-8 is
 * emitted as {@code base64:<payload>}. The same prefix is accepted on input, which makes the
 * round trip lossless for binary data (Bytes.toBytes(long) row keys, protobuf blobs, ...).
 */
final class Codec {

    static final String BASE64_PREFIX = "base64:";

    private Codec() {}

    /** Decodes a JSON string argument into HBase bytes. */
    static byte[] toBytes(String value) {
        if (value == null) {
            return null;
        }
        if (value.startsWith(BASE64_PREFIX)) {
            return Base64.getDecoder().decode(value.substring(BASE64_PREFIX.length()));
        }
        return value.getBytes(StandardCharsets.UTF_8);
    }

    /** Encodes HBase bytes for JSON output, falling back to base64 for non-text data. */
    static String toText(byte[] bytes) {
        if (bytes == null) {
            return null;
        }
        if (bytes.length == 0) {
            return "";
        }
        String decoded = decodeStrictUtf8(bytes);
        // Text that happens to begin with "base64:" has to be encoded too, or reading it back
        // would decode it as binary and corrupt it. This keeps the round trip lossless.
        if (decoded == null || hasControlCharacters(decoded) || decoded.startsWith(BASE64_PREFIX)) {
            return BASE64_PREFIX + Base64.getEncoder().encodeToString(bytes);
        }
        return decoded;
    }

    private static String decodeStrictUtf8(byte[] bytes) {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            CharBuffer out = decoder.decode(ByteBuffer.wrap(bytes));
            return out.toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    private static boolean hasControlCharacters(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\t' || c == '\n' || c == '\r') {
                continue;
            }
            if (Character.isISOControl(c)) {
                return true;
            }
        }
        return false;
    }
}
