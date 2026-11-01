package com.tile.screenoff;

import java.util.Collections;
import java.util.Map;

/** 一个只读的 HTTP 请求模型。 */
public class HttpRequest {

    private final String method;
    private final String target;
    private final String path;
    private final String query;
    private final Map<String, String> headers;

    public HttpRequest(String method, String target, String path, String query, Map<String, String> headers) {
        this.method = method;
        this.target = target;
        this.path = path;
        this.query = query;
        this.headers = headers == null ? Collections.<String, String>emptyMap() : headers;
    }

    public String getMethod() {
        return method;
    }

    /** 原始请求目标，例如 /state?x=1 */
    public String getTarget() {
        return target;
    }

    /** 去掉查询串后的路径，例如 /state */
    public String getPath() {
        return path;
    }

    public String getQuery() {
        return query;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }
}
