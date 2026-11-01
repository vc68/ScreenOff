package com.tile.screenoff;

import java.nio.charset.StandardCharsets;

/** 构造 HTTP 响应的小工具，避免在业务代码里到处拼字符串。 */
public final class Http {

    public static final String TEXT = "text/plain; charset=utf-8";
    public static final String HTML = "text/html; charset=utf-8";
    public static final String JSON = "application/json; charset=utf-8";
    public static final String PNG = "image/png";

    private Http() {
    }

    public static byte[] build(int code, String contentType, byte[] body) {
        byte[] payload = body == null ? new byte[0] : body;
        String head = "HTTP/1.1 " + code + " " + statusText(code) + "\r\n"
                + "Content-Type: " + contentType + "\r\n"
                + "Content-Length: " + payload.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n"
                + "\r\n";
        byte[] headBytes = head.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[headBytes.length + payload.length];
        System.arraycopy(headBytes, 0, out, 0, headBytes.length);
        System.arraycopy(payload, 0, out, headBytes.length, payload.length);
        return out;
    }

    public static byte[] plain(int code, String text) {
        return build(code, TEXT, text.getBytes(StandardCharsets.UTF_8));
    }

    public static byte[] html(int code, byte[] body) {
        return build(code, HTML, body);
    }

    public static byte[] json(int code, String json) {
        return build(code, JSON, json.getBytes(StandardCharsets.UTF_8));
    }

    public static byte[] png(byte[] body) {
        return build(200, PNG, body);
    }

    private static String statusText(int code) {
        switch (code) {
            case 200:
                return "OK";
            case 204:
                return "No Content";
            case 400:
                return "Bad Request";
            case 404:
                return "Not Found";
            case 405:
                return "Method Not Allowed";
            case 500:
                return "Internal Server Error";
            case 503:
                return "Service Unavailable";
            default:
                return "OK";
        }
    }
}
