package scaffolding;

import okhttp3.Request;
import okhttp3.Response;
import okhttp3.sse.EventSource;
import okhttp3.sse.EventSourceListener;
import okhttp3.sse.EventSources;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.net.URI;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class SseTestClient extends EventSourceListener{

    private final URI uri;
    private final List<String> messages = new LinkedList<>();
    private final CountDownLatch closeLatch = new CountDownLatch(1);
    private final CountDownLatch errorLatch = new CountDownLatch(1);
    private EventSource eventSource;

    private SseTestClient(URI uri) {
        this.uri = uri;
    }

    public SseTestClient start() {
        final EventSource.Factory factory = EventSources.createFactory(ClientUtils.client);
        Request request = new Request.Builder().url(uri.toString()).build();
        this.eventSource = factory.newEventSource(request, this);
        return this;
    }

    @Override
    public void onOpen(@NotNull EventSource eventSource, @NotNull Response response) {
        messages.add("onOpen:");
    }

    @Override
    public void onClosed(@NotNull EventSource eventSource) {
        messages.add("onClosed:");
        closeLatch.countDown();
    }

    @Override
    public void onEvent(@NotNull EventSource eventSource, @Nullable String id, @Nullable String type, @NotNull String data) {
        messages.add(String.format("onEvent: id=%s, type=%s, data=%s", id, type, data));
    }

    @Override
    public void onFailure(@NotNull EventSource eventSource, @Nullable Throwable t, @Nullable Response response) {
        messages.add(String.format("onFailure: message=%s", t.getMessage()));
        errorLatch.countDown();
    }

    public List<String> getMessages() {
        return messages;
    }

    public void stop() {
        this.eventSource.cancel();
    }

    public void waitUntilError(long timeout, TimeUnit unit) throws InterruptedException {
        errorLatch.await(timeout, unit);
    }

    public void waitUntilClose(long timeout, TimeUnit unit) throws InterruptedException {
        closeLatch.await(timeout, unit);
    }

    public void waitMessageListSizeGreaterThan(int expectSize, long timeout, TimeUnit unit) throws InterruptedException {
        final long startTime = System.currentTimeMillis();
        while (true) {
            final long elapsed = System.currentTimeMillis() - startTime;
            if (elapsed >= TimeUnit.MILLISECONDS.convert(timeout, unit)) throw new RuntimeException(String.format("Time elapsed %s ms", elapsed));
            if (messages.size() >= expectSize) break;
            Thread.sleep(100L);
        }
    }

    public static SseTestClient startSse(URI uri) {
        final SseTestClient client = new SseTestClient(uri);
        return client.start();
    }
}
