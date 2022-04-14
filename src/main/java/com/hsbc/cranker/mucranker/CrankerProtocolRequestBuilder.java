package com.hsbc.cranker.mucranker;

/**
 * Request from router to connector
 */
class CrankerProtocolRequestBuilder {
    private static final String REQUEST_HAS_NO_BODY_MARKER = "_2";
    private static final String REQUEST_BODY_PENDING_MARKER = "_1";
    private static final String REQUEST_BODY_ENDED_MARKER = "_3";

    /**
     * CRANKER_PROTOCOL_VERSION_1_0
     * request msg format:
     * <p>
     * ======msg without body========
     * ** GET /modules/uui-allocation/1.0.68/uui-allocation.min.js.map HTTP/1.1\n
     * ** [headers]\n
     * ** \n
     * ** endmarker
     * <p>
     * <p>
     * OR
     * <p>
     * =====msg with body part 1=======
     * ** GET /modules/uui-allocation/1.0.68/uui-allocation.min.js.map HTTP/1.1\n
     * ** [headers]\n
     * ** \n
     * ** endmarker
     * =====msg with body part 2=========
     * ** [BINRAY BODY]
     * =====msg with body part 3=======
     * ** endmarker
     */
    private String requestLine;
    private HeadersBuilder headers;
    private String endMarker;

    public static CrankerProtocolRequestBuilder newBuilder() {
        return new CrankerProtocolRequestBuilder();
    }

    public CrankerProtocolRequestBuilder withRequestLine(String requestLine) {
        this.requestLine = requestLine;
        return this;
    }

    public CrankerProtocolRequestBuilder withRequestHeaders(HeadersBuilder headers) {
        this.headers = headers;
        return this;
    }

    public CrankerProtocolRequestBuilder withRequestBodyPending() {
        this.endMarker = REQUEST_BODY_PENDING_MARKER;
        return this;
    }

    public CrankerProtocolRequestBuilder withRequestHasNoBody() {
        this.endMarker = REQUEST_HAS_NO_BODY_MARKER;
        return this;
    }

    public CrankerProtocolRequestBuilder withRequestBodyEnded() {
        this.endMarker = REQUEST_BODY_ENDED_MARKER;
        return this;
    }

    public String build() {
        if (requestLine != null && headers != null) {
            return requestLine + "\n" + headers + "\n" + this.endMarker;
        } else {
            return this.endMarker;
        }
    }
}
