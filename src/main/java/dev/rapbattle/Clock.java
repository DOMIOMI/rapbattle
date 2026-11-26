package dev.rapbattle;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Single time source for gameplay timers and cooldowns.
 * In production {@link #now()} returns {@link System#currentTimeMillis()}; tests can shift it via {@link #advanceForTest(long)}.
 */
public final class Clock {
	private Clock() {}

	private static volatile long offsetMs;

	public static long now() {
		return System.currentTimeMillis() + offsetMs;
	}

	public static LocalDate today() {
		return LocalDate.ofInstant(Instant.ofEpochMilli(now()), ZoneId.systemDefault());
	}

	public static void advanceForTest(long deltaMs) {
		offsetMs += deltaMs;
	}

	public static void resetForTest() {
		offsetMs = 0L;
	}
}
