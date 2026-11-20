package com.crisisconnect.event;

import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;

/**
 * Very small publish/subscribe hub.
 * CopyOnWriteArrayList -> safe when the API thread publishes while the UI thread subscribes.
 */
public final class EventBus {

    private static final Logger LOG = Logger.getLogger(EventBus.class.getName());
    private static final EventBus INSTANCE = new EventBus();
    private static final int HISTORY_SIZE = 50;

    private final List<CrisisEventListener> listeners = new CopyOnWriteArrayList<>();
    private final LinkedList<CrisisEvent> history = new LinkedList<>();

    private EventBus() { }

    public static EventBus get() { return INSTANCE; }

    public void subscribe(CrisisEventListener l) { listeners.add(l); }

    public void unsubscribe(CrisisEventListener l) { listeners.remove(l); }

    public void publish(CrisisEvent event) {
        synchronized (history) {
            history.addFirst(event);
            if (history.size() > HISTORY_SIZE) history.removeLast();
        }
        LOG.info("EVENT " + event.type() + ": " + event.message());
        for (CrisisEventListener l : listeners) {
            try { l.onEvent(event); }
            catch (RuntimeException e) { LOG.warning("Listener failed: " + e.getMessage()); }
        }
    }

    public List<CrisisEvent> recent() {
        synchronized (history) { return List.copyOf(history); }
    }
}
