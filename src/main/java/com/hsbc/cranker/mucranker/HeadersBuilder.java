package com.hsbc.cranker.mucranker;

import io.muserver.Headers;

import java.util.Map;

class HeadersBuilder {

    private final Headers headers = Headers.http1Headers();

    public void appendHeader(String header, String value) {
        if (header.equalsIgnoreCase("cookie")) {
            // if multiple cookie headers exist, they should be combined into a single cookie header as per https://tools.ietf.org/html/rfc7540#section-8.1.2.5
            String cookieVal = value;
            if (headers.contains("cookie")) {
                cookieVal = headers.get("cookie") + "; " + value;
            }
            headers.set("cookie", cookieVal);
        } else {
            headers.add(header, value);
        }
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> header : headers) {
            sb.append(header.getKey()).append(':').append(header.getValue()).append('\n');
        }
        return sb.toString();
    }

    public Headers muHeaders() {
        return headers;
    }
}
