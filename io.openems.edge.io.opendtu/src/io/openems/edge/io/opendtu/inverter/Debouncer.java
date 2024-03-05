package io.openems.edge.io.opendtu.inverter;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class Debouncer {
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final ConcurrentHashMap<String, Future<?>> scheduledTasks = new ConcurrentHashMap<>();
    private final long delay;

    public Debouncer(long delay, TimeUnit unit) {
        this.delay = unit.toMillis(delay);
    }

    /**
     * Schedules the execution of a task associated with a specific key to run after a predefined delay,
     * effectively debouncing the task. If a task with the same key is already scheduled but not yet executed,
     * it will be cancelled and rescheduled. This ensures that only the last submitted task for a given key
     * within the debounce delay period is executed.
     * 
     * @param key The key associated with the task. Used to identify and manage debouncing of tasks.
     * @param task The {@link Runnable} task to be executed after the debounce delay.
     */
    public void debounce(String key, Runnable task) {
        this.scheduledTasks.compute(key, (k, v) -> {
            if (v != null) {
                v.cancel(false);
            }
            return this.scheduler.schedule(() -> {
                this.scheduledTasks.remove(k);
                task.run();
            }, this.delay, TimeUnit.SECONDS);
        });
    }


    /**
     * Initiates an immediate shutdown of the scheduler. This attempts to stop all actively executing tasks
     * at the time of the call and halts the processing of waiting tasks.
     * 
     * <p>Calling this method will prevent any further tasks from being scheduled with this scheduler.
     * Tasks that are already running at the time of this call are attempted to be stopped, which may
     * involve interrupting thread execution.</p>
     */
    public void shutdown() {
        this.scheduler.shutdownNow();
    }

}
