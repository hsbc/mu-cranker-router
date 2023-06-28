package scaffolding;

import org.hamcrest.Matcher;

import static org.hamcrest.MatcherAssert.assertThat;

public class AssertUtils {
    public static <T> void assertEventually(Func<T> actual, Matcher<? super T> matcher) {
        AssertionError toThrow = null;
        for (int i = 0; i < 100; i++) {
            try {
                assertThat(actual.apply(), matcher);
                return;
            } catch (AssertionError e) {
                toThrow = e;
                try {
                    Thread.sleep(50);
                } catch (InterruptedException ex) {
                    throw new RuntimeException(ex);
                }
            } catch (Exception e) {
                throw new AssertionError("Error while getting value", e);
            }
        }
        throw toThrow;
    }

    public interface Func<V> {
        V apply() throws Exception;
    }
}
