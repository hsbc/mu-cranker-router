package com.hsbc.cranker.jdkconnector;

import java.util.Arrays;

/**
 * Request from router to connector
 */
class CrankerRequestParser {
    public static final String REQUEST_BODY_PENDING_MARKER = "_1";
    public static final String REQUEST_HAS_NO_BODY_MARKER = "_2";
    public static final String REQUEST_BODY_ENDED_MARKER = "_3";

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
    public String httpMethod;
    public String dest;
    public String[] headers;
    private String endMarker;

    public CrankerRequestParser(CharSequence msg) {
        if (msg.equals(REQUEST_BODY_ENDED_MARKER)) {
            this.endMarker = REQUEST_BODY_ENDED_MARKER;
        } else {
            String[] msgArr = msg.toString().split("\n");
            String request = msgArr[0];
            String[] bits = request.split(" ");
            this.httpMethod = bits[0];
            this.dest = bits[1];
            this.headers = Arrays.copyOfRange(msgArr, 1, msgArr.length - 1);
            this.endMarker = msgArr[msgArr.length - 1];
        }
    }
    public long bodyLength() {
        for (String headerLine : headers) {
            // line sample: "Content-Length:100000"
            if (headerLine.toLowerCase().startsWith("content-length:")) {
                String[] split = headerLine.split(":");
                if (split.length == 2) {
                    return Long.parseLong(split[1].trim());
                }
            }
        }
        return -1;
    }

    public boolean requestBodyPending() {
        return endMarker.equals(REQUEST_BODY_PENDING_MARKER);
    }

    public boolean requestBodyEnded() {
        return endMarker.equals(REQUEST_BODY_ENDED_MARKER);
    }

    public void sendRequestToTarget(RequestCallback sendRequestCallback) {
        if (endMarker.equals(REQUEST_BODY_PENDING_MARKER) || endMarker.equals(REQUEST_HAS_NO_BODY_MARKER)) {
            sendRequestCallback.callback();
        }
    }

    public interface RequestCallback {
        void callback();
    }

    @Override
    public String toString() {
        return "CrankerRequestParser{" + httpMethod + " " + dest + '}';
    }
}
