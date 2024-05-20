package com.hsbc.cranker.mucranker;

import io.muserver.*;

import jakarta.ws.rs.NotFoundException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * This is a convenience class to make it easy to serve a favicon.ico file for your router. Place an <code>.ico</code>
 * file on your classpath, then create a FavIconHandler with {@link #fromClassPath(String)} and add it to your mu-server.
 */
public class FavIconHandler implements MuHandler {

    private final byte[] favicon;

    private FavIconHandler(byte[] favicon) {
        this.favicon = favicon;
    }

    /**
     * Creates a Mu Handler that serves a favicon file.
     * @param iconPath The classpath path to your <code>favicon.ico</code> file, for example <code>/web/favicon.ico</code>
     * @return A handler that you can add to {@link MuServerBuilder#addHandler(MuHandler)}
     * @throws IOException Thrown if the icon doesn't exist or cannot be read
     */
    public static FavIconHandler fromClassPath(String iconPath) throws IOException {
        byte[] bytes;
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             InputStream fav = FavIconHandler.class.getResourceAsStream(iconPath)) {
            Mutils.copy(fav, baos, 8192);
            bytes = baos.toByteArray();
        }
        return new FavIconHandler(bytes);
    }

    @Override
    public boolean handle(MuRequest req, MuResponse resp) throws Exception {
        String target = req.uri().getPath();
        if (target.equals("/favicon.ico") && req.method() == Method.GET) {
            if (favicon == null) {
                throw new NotFoundException();
            }
            resp.status(200);
            resp.contentType(ContentTypes.IMAGE_X_ICON);
            resp.headers().set(HeaderNames.CONTENT_LENGTH, favicon.length);
            resp.headers().set(HeaderNames.CACHE_CONTROL, "max-age=360000,public");
            resp.outputStream().write(favicon);
            return true;
        }
        return false;
    }
}
