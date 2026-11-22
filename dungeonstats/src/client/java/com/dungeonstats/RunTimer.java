package com.dungeonstats;

/**
 * Time spent in the current dungeon run. Shown on the HUD only, never saved.
 * Starts when you enter a dungeon. Stops on "Dungeon completed in 724.6 seconds!" and then shows the
 * server's time. Also stops if you lose all your lives. Re-entering the same unfinished run keeps it going.
 */
public class RunTimer {
	private long startedAt = -1;
	private long stoppedAt = -1;
	/** Set when the server announces the completion time; shown instead of the live timer. */
	private String completedText = null;

	public void start() {
		startedAt = System.currentTimeMillis();
		stoppedAt = -1;
		completedText = null;
	}

	/** "Dungeon completed in 724.6 seconds!": stop, and show the server's time from now on. */
	public void complete(String secondsText) {
		stop();
		String mmss = "";
		try {
			long seconds = (long) Double.parseDouble(secondsText.replace(',', '.'));
			mmss = " (" + formatSeconds(seconds) + ")";
		} catch (NumberFormatException ignored) {
		}
		completedText = "Completed in " + secondsText + " seconds" + mmss;
	}

	/** Freezes the timer at its current value. Does nothing if it isn't running. */
	public void stop() {
		if (startedAt >= 0 && stoppedAt < 0) stoppedAt = System.currentTimeMillis();
	}

	public boolean isRunning() {
		return startedAt >= 0 && stoppedAt < 0;
	}

	/** The HUD line: "Time: 12:34" while running, or "Completed in 724.6 seconds (12:04)" once done. */
	public String hudText() {
		if (completedText != null) return completedText;
		if (startedAt < 0) return "Time: --:--";
		long end = stoppedAt >= 0 ? stoppedAt : System.currentTimeMillis();
		return "Time: " + formatSeconds(Math.max(0, (end - startedAt) / 1000));
	}

	/** "12:34", or "1:02:03" past an hour. */
	private static String formatSeconds(long seconds) {
		long h = seconds / 3600, m = (seconds % 3600) / 60, s = seconds % 60;
		return h > 0 ? String.format("%d:%02d:%02d", h, m, s) : String.format("%02d:%02d", m, s);
	}
}
