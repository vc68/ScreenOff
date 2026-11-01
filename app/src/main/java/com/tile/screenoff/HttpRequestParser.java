package com.tile.screenoff;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * 无状态的 HTTP 请求解析器。
 * <p>
 * 旧实现每收到一段数据都要把整个缓冲区复制一遍（O(n^2)），而且解析结果依赖实例状态。
 * 现在改成一次性解析完整请求字节，既快也不会串包。
 */
public final class HttpRequestParser {

    private HttpRequestParser() {
    }

    public static HttpRequest parse(byte[] raw) {
        if (raw == null || raw.length == 0) return null;

        String text = new String(raw, StandardCharsets.ISO_8859_1);
        int headerEnd = text.indexOf("\r\n\r\n");
        String headerPart = headerEnd >= 0 ? text.substring(0, headerEnd) : text;
        String[] lines = headerPart.split("\r\n");
        if (lines.length == 0) return null;

        String[] startLine = lines[0].split(" ");
        if (startLine.length < 2) return null;

        String method = startLine[0];
        String target = startLine[1];
        if (target.isEmpty()) return null;

        String path = target;
        String query = null;
        int q = target.indexOf('?');
        if (q >= 0) {
            path = target.substring(0, q);
            query = target.substring(q + 1);
        }

        Map<String, String> headers = new HashMap<>();
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.isEmpty()) break;
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            headers.put(line.substring(0, colon).trim().toLowerCase(java.util.Locale.US),
                    line.substring(colon + 1).trim());
        }

        return new HttpRequest(method, target, path, query, headers);
    }
}
