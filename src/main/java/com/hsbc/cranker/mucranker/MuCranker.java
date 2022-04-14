package com.hsbc.cranker.mucranker;

import io.muserver.MuServer;

import java.io.InputStream;
import java.util.Properties;

class MuCranker {

    /**
     * @return Returns the current version of MuCranker, or 0.x if unknown
     */
    public static String artifactVersion() {
        try {
            Properties props = new Properties();
            try (InputStream in = MuServer.class.getResourceAsStream("/META-INF/maven/com.hsbc.cranker/mu-cranker-router/pom.properties")) {
                if (in == null) {
                    return "0.x";
                }
                props.load(in);
            }
            return props.getProperty("version");
        } catch (Exception ex) {
            return "0.x";
        }
    }
}
