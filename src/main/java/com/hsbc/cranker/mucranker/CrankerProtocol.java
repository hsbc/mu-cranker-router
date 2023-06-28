package com.hsbc.cranker.mucranker;

class CrankerProtocol {

    static final String CRANKER_PROTOCOL_VERSION_1_0 = "1.0";
    static final String CRANKER_PROTOCOL_VERSION_2_0 = "2.0";
    static final String CRANKER_PROTOCOL_VERSION_3_0 = "3.0";
    static final String SUPPORTING_HTTP_VERSION_1_1 = "HTTP/1.1";

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
     * <p>
     * <p>
     * response msg format:
     * <p>
     * ** HTTP/1.1 200 OK\n
     * ** [headers]\n
     * ** \n
     */


    public static class CrankerProtocolVersionNotSupportedException extends RuntimeException {
        public CrankerProtocolVersionNotSupportedException(String reason) {
            super(reason);
        }
    }

    static class CrankerProtocolVersionNotFoundException extends RuntimeException {
        public CrankerProtocolVersionNotFoundException(String reason) {
            super(reason);
        }
    }
}
