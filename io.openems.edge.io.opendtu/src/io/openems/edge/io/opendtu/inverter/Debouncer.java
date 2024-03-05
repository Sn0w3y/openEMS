package io.openems.edge.io.opendtu.inverter;

import java.util.concurrent.*;

public class Debouncer {
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final ConcurrentHashMap<String, Future<?>> scheduledTasks = new ConcurrentHashMap<>();
    private final long delay;

    public Debouncer(long delay, TimeUnit unit) {
        this.delay = unit.toMillis(delay);
    }

    public void debounce(String key, Runnable task) {
        scheduledTasks.compute(key, (k, v) -> {
            if (v != null) {
                v.cancel(false);
            }
            return scheduler.schedule(() -> {
                scheduledTasks.remove(k);
                task.run();
            }, delay, TimeUnit.SECONDS);
        });
    }

    public void shutdown() {
        scheduler.shutdownNow();
    }
}
