package scaffolding;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public interface Action {
    Logger log = LoggerFactory.getLogger(Action.class);

    static void swallowException(Action action) {
        try {
            action.run();
        } catch (Exception e) {
            log.info("Ignoring exception: " + e.getMessage());
        }
    }

    void run() throws Exception;
}
