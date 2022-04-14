package com.hsbc.cranker.jdkconnector;

/**
 * Response from connector to router
 */
class CrankerResponseBuilder {
    /**
     * CRANKER_PROTOCOL_VERSION_1_0
     * <p>
     * response msg format:
     * <p>
     * =====Part 1===================
     * ** HTTP/1.1 200 OK\n
     * ** [headers]\n
     * ** \n
     * ===== Part 2 (if msg with body)======
     * **Binary Content
     */
    private static final String HTTP1_1 = "HTTP/1.1";
    private int status;
    private String reason;
    private HeadersBuilder headers;

    public static CrankerResponseBuilder newBuilder() {
        return new CrankerResponseBuilder();
    }

    public CrankerResponseBuilder withResponseStatus(int status) {
        this.status = status;
        return this;
    }

    public CrankerResponseBuilder withResponseReason(String reason) {
        this.reason = reason;
        return this;
    }

    public CrankerResponseBuilder withResponseHeaders(HeadersBuilder headers) {
        this.headers = headers;
        return this;
    }

    public String build() {
        return HTTP1_1 + " " + status + " " + reason + "\n" + headers.toString();
    }
}
