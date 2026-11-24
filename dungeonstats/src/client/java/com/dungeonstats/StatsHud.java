package com.dungeonstats;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Small panel in the corner of the screen showing the current dungeon run's stats. */
public final class StatsHud {
	private static final int TITLE_COLOR = 0xFFFFAA00; // gold (colors are ARGB: keep the FF alpha)
	private static final int TEXT_COLOR = 0xFFFFFFFF;
	private static final int PAUSED_COLOR = 0xFFFF5555;
	private static final int TIMER_COLOR = 0xFFFFFF55; // yellow (no leaderboard to compare with)
	private static final int TIMER_FAST_COLOR = 0xFF55FF55; // green: faster than #1
	private static final int TIMER_MID_COLOR = 0xFFFFAA00; // orange: between #1 and #5
	private static final int TIMER_SLOW_COLOR = 0xFFFF5555; // red: slower than #5
	private static final int KILLS_COLOR = 0xFFFF7777; // light red
	private static final int REWARD_COLOR = 0xFF55FFFF; // aqua header for the end-reward section
	private static final int REWARD_TEXT_COLOR = 0xFFAAFFFF;
	private static final int LEADERBOARD_COLOR = 0xFF55FF55; // green
	private static final int LEADERBOARD_TEXT_COLOR = 0xFFAAFFAA;
	private static final int LEADERBOARD_DIM_COLOR = 0xFFAAAAAA; // grey when there's nothing to show
	private static final int ALL_TIME_COLOR = 0xFFFF55FF; // light purple headers for all-time totals
	private static final int ALL_TIME_TEXT_COLOR = 0xFFFFAAFF;
	private static final int BACKGROUND = 0x80000000; // half-transparent black

	private static final StatsData.Tally EMPTY = new StatsData.Tally();

	private StatsHud() {
	}

	/** Where the box was last drawn, in screen pixels (after scaling). Used by the drag-to-move screen. */
	public record Bounds(float left, float top, float width, float height) {
		public boolean contains(double x, double y) {
			return x >= left && x <= left + width && y >= top && y <= top + height;
		}
	}

	/** Called by the game every frame (HUD layer). */
	public static void extract(GuiGraphicsExtractor graphics, DeltaTracker tickCounter) {
		StatsData stats = DungeonStatsClient.stats();
		Minecraft client = Minecraft.getInstance();
		if (!stats.hudVisible || client.player == null) return;
		draw(graphics);
	}

	/** Draws the box at its configured place and size, and returns where it went. */
	public static Bounds draw(GuiGraphicsExtractor graphics) {
		StatsData stats = DungeonStatsClient.stats();
		ModConfig config = DungeonStatsClient.config();
		Font font = Minecraft.getInstance().font;
		// Show the run in progress, or else the last finished one.
		// Empty counters if there's neither, without creating a run just for drawing.
		StatsData.RunStats run = stats.currentRun != null ? stats.currentRun : stats.lastRun;
		StatsData.Tally level = run != null ? run : EMPTY;

		String title = run != null ? stats.label(run) : stats.label(stats.currentLevel);
		if (!stats.tracking) {
			title += " (paused)";
		} else if (run != null && run.completed) {
			title += " (done)";
		}

		List<Line> lines = new ArrayList<>();
		lines.add(new Line(title, stats.tracking ? TITLE_COLOR : PAUSED_COLOR));
		int dungeon = run != null ? run.level : stats.currentLevel;
		if (config.showTimer) lines.add(new Line(DungeonStatsClient.timer().hudText(), timerColor(stats, dungeon)));
		addLeaderboardLines(lines, config, stats, dungeon);
		if (config.showMobCounter) lines.add(new Line("Mobs killed: " + DungeonStatsClient.kills().kills(), KILLS_COLOR));

		// One section for the run: chests while you're in the dungeon, then the end rewards once you
		// start claiming them (until you enter the next dungeon).
		if (level.rewardsClaimed > 0) {
			// End-of-dungeon rewards ("Collected ... from the loot pool")
			lines.add(new Line("End rewards claimed: " + level.rewardsClaimed, REWARD_COLOR));
			config.displayLoot(level.rewardLoot).forEach((name, amount) ->
					lines.add(new Line("  " + name + ": " + amount, REWARD_TEXT_COLOR)));
		} else {
			// Normal chests ("Added ... to the loot pool")
			// "Chests looted: 34 (+1) / 45": bonus chests in brackets, chests in the dungeon after the slash.
			int bonus = level instanceof StatsData.RunStats r ? r.bonusChests : 0;
			// The server counts every player's chests: split it between the players (green faces in the sidebar).
			int serverTotal = level instanceof StatsData.RunStats r ? r.chestsInDungeon : 0;
			int inDungeon = Math.round((float) serverTotal / Math.max(1, DungeonStatsClient.sidebar().players()));
			lines.add(new Line("Chests looted: " + level.chestsLooted + (bonus > 0 ? " (+" + bonus + ")" : "")
					+ (inDungeon > 0 ? " / " + inDungeon : ""), TEXT_COLOR));
			config.displayLoot(level.loot).forEach((name, amount) ->
					lines.add(new Line("  " + name + ": " + amount, TEXT_COLOR))); // embers folded into dragon eggs
		}

		// All-time totals (each section can be switched on in /dstats settings)
		StatsData.Tally total = stats.total != null ? stats.total : EMPTY;
		if (config.showAllTimeChests) {
			lines.add(new Line("All-time chests: " + total.chestsLooted, ALL_TIME_COLOR));
			config.displayLoot(total.loot).forEach((name, amount) ->
					lines.add(new Line("  " + name + ": " + amount, ALL_TIME_TEXT_COLOR)));
		}
		if (config.showAllTimeRewards) {
			lines.add(new Line("All-time end rewards: " + total.rewardsClaimed, ALL_TIME_COLOR));
			config.displayLoot(total.rewardLoot).forEach((name, amount) ->
					lines.add(new Line("  " + name + ": " + amount, ALL_TIME_TEXT_COLOR)));
		}

		int width = 0;
		for (Line line : lines) width = Math.max(width, font.width(line.text()));

		int lineHeight = font.lineHeight + 1;
		int padding = 2;
		// Size of the box at normal (1.0) scale, padding included.
		int boxWidth = width + padding * 2;
		int boxHeight = lineHeight * lines.size() + padding * 2 - 1;

		// Where the box goes on screen, after scaling, measured from the chosen corner.
		float scale = config.clampedScale();
		float scaledWidth = boxWidth * scale;
		float scaledHeight = boxHeight * scale;
		boolean right = config.hudCorner != null && config.hudCorner.endsWith("right");
		boolean bottom = config.hudCorner != null && config.hudCorner.startsWith("bottom");
		float left = right ? graphics.guiWidth() - config.hudX - scaledWidth : config.hudX;
		float top = bottom ? graphics.guiHeight() - config.hudY - scaledHeight : config.hudY;

		graphics.pose().pushMatrix();
		graphics.pose().translate(left, top);
		graphics.pose().scale(scale);

		graphics.fill(0, 0, boxWidth, boxHeight, BACKGROUND);
		for (int i = 0; i < lines.size(); i++) {
			Line line = lines.get(i);
			graphics.text(font, line.text(), padding, padding + lineHeight * i, line.color(), true);
		}

		graphics.pose().popMatrix();
		return new Bounds(left, top, scaledWidth, scaledHeight);
	}

	/** The dungeon's saved leaderboard, fastest first (empty if not loaded). */
	private static List<StatsData.LeaderboardEntry> sortedEntries(StatsData stats, int dungeon) {
		StatsData.Leaderboard board = stats.leaderboards.get(dungeon);
		List<StatsData.LeaderboardEntry> entries = new ArrayList<>();
		if (board != null && board.entries != null) {
			for (StatsData.LeaderboardEntry e : board.entries) {
				if (LeaderboardCompare.parseTime(e.time) != null) entries.add(e);
			}
			entries.sort((a, b) -> Double.compare(LeaderboardCompare.parseTime(a.time), LeaderboardCompare.parseTime(b.time)));
		}
		return entries;
	}

	/**
	 * Timer colour compared with the leaderboard: green while faster than #1, orange between #1 and #5,
	 * red once slower than #5. Yellow when there's no leaderboard or no timer.
	 */
	private static int timerColor(StatsData stats, int dungeon) {
		double elapsed = DungeonStatsClient.timer().elapsedSeconds();
		List<StatsData.LeaderboardEntry> entries = sortedEntries(stats, dungeon);
		if (elapsed < 0 || entries.isEmpty()) return TIMER_COLOR;

		double first = LeaderboardCompare.parseTime(entries.get(0).time);
		double last = LeaderboardCompare.parseTime(entries.get(entries.size() - 1).time);
		if (elapsed < first) return TIMER_FAST_COLOR;
		if (elapsed <= last) return TIMER_MID_COLOR;
		return TIMER_SLOW_COLOR;
	}

	/** The "Leaderboard" HUD option (see ModConfig.hudLeaderboardMode). */
	private static void addLeaderboardLines(List<Line> lines, ModConfig config, StatsData stats, int dungeon) {
		String mode = config.hudLeaderboardMode == null ? "off" : config.hudLeaderboardMode;
		if (mode.equals("off")) return;

		List<StatsData.LeaderboardEntry> entries = sortedEntries(stats, dungeon);

		switch (mode) {
			case "fastest" -> {
				if (entries.isEmpty()) {
					lines.add(new Line("Record: not loaded", LEADERBOARD_DIM_COLOR));
				} else {
					StatsData.LeaderboardEntry first = entries.get(0);
					lines.add(new Line("Record: " + first.time + " (" + first.player + ")", LEADERBOARD_COLOR));
				}
			}
			case "personal_best" -> {
				StatsData.LevelStats level = stats.levels.get(dungeon);
				if (level == null || level.bestTime <= 0) {
					lines.add(new Line("Your best: none yet", LEADERBOARD_DIM_COLOR));
				} else {
					lines.add(new Line("Your best: " + LeaderboardCompare.formatTime(level.bestTime), LEADERBOARD_COLOR));
				}
			}
			case "top5" -> {
				if (entries.isEmpty()) {
					lines.add(new Line("Leaderboard: not loaded", LEADERBOARD_DIM_COLOR));
				} else {
					lines.add(new Line("Leaderboard:", LEADERBOARD_COLOR));
					for (StatsData.LeaderboardEntry e : entries) {
						lines.add(new Line("  #" + e.rank + " " + e.player + " " + e.time, LEADERBOARD_TEXT_COLOR));
					}
				}
			}
			case "next_to_beat" -> {
				if (entries.isEmpty()) {
					lines.add(new Line("To beat: leaderboard not loaded", LEADERBOARD_DIM_COLOR));
					break;
				}
				double elapsed = Math.max(0, DungeonStatsClient.timer().elapsedSeconds());
				// The fastest time the clock hasn't passed yet: #1 until you're slower than #1, then #2, ...
				StatsData.LeaderboardEntry target = null;
				for (StatsData.LeaderboardEntry e : entries) {
					if (LeaderboardCompare.parseTime(e.time) > elapsed) {
						target = e;
						break;
					}
				}
				if (target == null) {
					StatsData.LeaderboardEntry last = entries.get(entries.size() - 1);
					lines.add(new Line("To beat: slower than #" + last.rank + " (" + last.time + ")", LEADERBOARD_DIM_COLOR));
				} else {
					long left = (long) Math.ceil(LeaderboardCompare.parseTime(target.time) - elapsed);
					lines.add(new Line("To beat: #" + target.rank + " " + target.player + " " + target.time
							+ " (" + RunTimer.formatSeconds(left) + " left)", LEADERBOARD_COLOR));
				}
			}
			default -> {
			}
		}
	}

	private record Line(String text, int color) {
	}
}
