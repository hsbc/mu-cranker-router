package scaffolding;

import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.HttpUrl;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public class InMemCookieJar implements CookieJar {
    ConcurrentHashMap<String, List<Cookie>> all = new ConcurrentHashMap<>();

    List<Cookie> listFor(HttpUrl url) {
        all.computeIfAbsent(url.host(), s -> new ArrayList<>());
        return all.get(url.host());
    }

    public void saveFromResponse(@NotNull HttpUrl httpUrl, @NotNull List<Cookie> list) {
        listFor(httpUrl).addAll(list);
    }

    @NotNull
    public List<Cookie> loadForRequest(@NotNull HttpUrl httpUrl) {
        return listFor(httpUrl);
    }
}
