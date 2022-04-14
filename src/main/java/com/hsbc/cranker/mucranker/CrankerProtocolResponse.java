package com.hsbc.cranker.mucranker;

import java.util.Arrays;

/**
 * Response from connector for router
 */
class CrankerProtocolResponse {
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
    public String[] headers;
    private final int status;

    public CrankerProtocolResponse(String message) {
        String[] messageArr = message.split("\n");
        String[] bits = messageArr[0].split(" ");
        this.status = Integer.parseInt(bits[1]);
        this.headers = Arrays.copyOfRange(messageArr, 1, messageArr.length);
    }

    public int getStatus() {
        return status;
    }
}
