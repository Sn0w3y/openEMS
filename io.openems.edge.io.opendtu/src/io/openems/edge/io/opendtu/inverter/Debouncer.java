package io.openems.edge.io.opendtu.inverter;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class Debouncer {
	private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
	private final ConcurrentHashMap<String, Future<?>> scheduledTasks = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<String, Runnable> lastTaskPerKey = new ConcurrentHashMap<>();
	private final long delay;

	public Debouncer(long delay, TimeUnit unit) {
		this.delay = unit.toMillis(delay);
	}

	/**
	 * Schedules the execution of the most recently submitted task associated with a
	 * specific key to run after a predefined delay, effectively debouncing the
	 * task. If a task with the same key is already scheduled but not yet executed,
	 * it will be cancelled. A new task is then scheduled with the delay, replacing
	 * any previously scheduled task for that key. This mechanism ensures that,
	 * within the debounce delay period, only the last submitted task for a given
	 * key is executed, allowing for efficient handling of frequent updates by
	 * executing only the most recent action requested.
	 * 
	 * @param key  The key associated with the task. Used to uniquely identify and
	 *             manage debouncing of tasks.
	 * @param task The {@link Runnable} task to be executed after the debounce
	 *             delay. This task represents the most recent action to be
	 *             performed for the given key, ensuring that only the latest
	 *             command influences the system state after the debounce period.
	 */
	public void debounce(String key, Runnable task) {
		this.lastTaskPerKey.put(key, task);

		Future<?> previous = this.scheduledTasks.get(key);
		if (previous != null) {
			previous.cancel(false);
		}

		Future<?> future = this.scheduler.schedule(() -> {
			Runnable lastTask = this.lastTaskPerKey.remove(key);
			if (lastTask != null) {
				lastTask.run();
			}
			this.scheduledTasks.remove(key);
		}, this.delay, TimeUnit.MILLISECONDS);

		this.scheduledTasks.put(key, future);
	}

	/**
	 * Initiates an immediate shutdown of the scheduler. This attempts to stop all
	 * actively executing tasks at the time of the call and halts the processing of
	 * waiting tasks.
	 * 
	 * <p>
	 * Calling this method will prevent any further tasks from being scheduled with
	 * this scheduler. Tasks that are already running at the time of this call are
	 * attempted to be stopped, which may involve interrupting thread execution.
	 * </p>
	 */
	public void shutdown() {
		this.scheduler.shutdownNow();
	}

}
