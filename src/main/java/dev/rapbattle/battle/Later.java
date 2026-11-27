package dev.rapbattle.battle;

import java.util.ArrayList;
import java.util.List;

/** Tiny server-thread scheduler: run something a bit later (boss finishers, delayed blasts). */
public final class Later {
	private Later() {}

	private record Task(long at, Runnable run) {}

	private static final List<Task> TASKS = new ArrayList<>();

	public static void run(long delayMs, Runnable r) { TASKS.add(new Task(dev.rapbattle.Clock.now() + delayMs, r)); }

	/** Server tick. */
	public static void tick() {
		long now = dev.rapbattle.Clock.now();
		for (Task t : new ArrayList<>(TASKS)) {
			if (now < t.at()) continue;
			TASKS.remove(t);
			try {
				t.run().run();
			} catch (RuntimeException e) {
				dev.rapbattle.RapBattle.LOG.error("Scheduled task failed", e);
			}
		}
	}

	static void clear() { TASKS.clear(); }
}
