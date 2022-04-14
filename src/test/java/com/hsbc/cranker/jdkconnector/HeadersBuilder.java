package com.hsbc.cranker.jdkconnector;

class HeadersBuilder {
    private final StringBuilder headers = new StringBuilder();

    public void appendHeader(String header, String value) {
        headers.append(header).append(":").append(value).append("\n");
    }

    @Override
    public String toString() {
        return headers.toString();
    }
}
