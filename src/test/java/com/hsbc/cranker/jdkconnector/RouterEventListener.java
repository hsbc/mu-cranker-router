package com.hsbc.cranker.jdkconnector;

import java.util.List;

/**
 * A listener for router events
 */
public interface RouterEventListener {

    /**
     * Called when the routers being listened to are changed.
     * <p>This includes no startup, where <code>added</code> will be generally be the list of initial routers to connect to.</p>
     * @param data The data containing the change to router registration
     */
    default void onRegistrationChanged(ChangeData data) {}

    /**
     * Called when the connector fails to connect to a router
     * <p>This may just be a temporary issue. The connector will automatically attempt reconnection after some back-off time.</p>
     * @param router The router that the connector attempted to connect to
     * @param exception The cause of the error
     */
    default void onSocketConnectionError(RouterRegistration router, Throwable exception) {}

    /**
     * The data about a change in router registration passed to {@link #onRegistrationChanged(ChangeData)}
     */
    class ChangeData {
        private final List<RouterRegistration> added;
        private final List<RouterRegistration> removed;
        private final List<RouterRegistration> unchanged;

        /**
         * @return The routers that have been newly registered
         */
        public List<RouterRegistration> added() {
            return added;
        }

        /**
         * @return The routers that are no longer being connected to
         */
        public List<RouterRegistration> removed() {
            return removed;
        }

        /**
         * @return The routers that remain unchanged
         */
        public List<RouterRegistration> unchanged() {
            return unchanged;
        }

        ChangeData(List<RouterRegistration> added, List<RouterRegistration> removed, List<RouterRegistration> unchanged) {
            this.added = added;
            this.removed = removed;
            this.unchanged = unchanged;
        }

        @Override
        public String toString() {
            return "ChangeData{" +
                "added=" + added +
                ", removed=" + removed +
                ", unchanged=" + unchanged +
                '}';
        }
    }

}
