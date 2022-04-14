package com.hsbc.cranker.jdkconnector;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

class HttpUtils {

    static final List<String> DISALLOWED_REQUEST_HEADERS;
    static {
        // Older JDK clients blocked headers they shouldn't. From JDK 12 these can be turned off
        // with a system property. See https://bugs.openjdk.java.net/browse/JDK-8213189
        // For JDK 11, any of "date", "from", "host", "origin", "referer", "via", "warning" will not be proxied.
        // Upgrade to JDK 12 or newer if any of these are important.
        setPropertyIfUnset("jdk.httpclient.allowRestrictedHeaders", "host,date,via,warning,from,origin,referer");

        // This detects these for the current JDK and will not forward any banned ones.
        HttpRequest.Builder builder = HttpRequest.newBuilder();
        Set<String> headersThatJDKMayReject = Set.of("date", "expect", "from", "host", "origin", "referer", "via", "warning");
        List<String> disallowed = new ArrayList<>();
        for (String header : headersThatJDKMayReject) {
            try {
                builder.header(header, "dummy");
            } catch (IllegalArgumentException e) {
                disallowed.add(header);
            }
        }
        disallowed.add("content-length"); // as the body publisher adds it
        DISALLOWED_REQUEST_HEADERS = Collections.unmodifiableList(disallowed);
    }

    static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    static HttpClient.Builder createHttpClientBuilder(boolean trustAll) {

        HttpClient.Builder builder = HttpClient.newBuilder();
        if (trustAll) {
            trustAll(builder);
        }
        return builder;
    }

    private static void setPropertyIfUnset(String key, String value) {
        String custom = System.getProperty(key, null);
        if (custom == null) {
            System.setProperty(key, value);
        }
    }

    private static void trustAll(HttpClient.Builder builder) {
        try {
            final TrustManager[] trustAllCerts = new TrustManager[]{
                new X509TrustManager() {
                    @Override
                    public void checkClientTrusted(X509Certificate[] chain, String authType) {
                    }
                    @Override
                    public void checkServerTrusted(X509Certificate[] chain, String authType) {
                    }
                    @Override
                    public X509Certificate[] getAcceptedIssuers() {
                        return new X509Certificate[0];
                    }
                }
            };
            SSLContext sslContext = SSLContext.getInstance("SSL");
            sslContext.init(null, trustAllCerts, new java.security.SecureRandom());
            builder.sslContext(sslContext);

        } catch (NoSuchAlgorithmException | KeyManagementException e) {
            throw new RuntimeException(e);
        }
    }

}
