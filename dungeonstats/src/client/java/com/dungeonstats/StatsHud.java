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
	private static final int TIMER_COLOR = 0xFFFFFF55; // yellow
	private static final int KILLS_COLOR = 0xFFFF7777; // light red
	private static final int REWARD_COLOR = 0xFF55FFFF; // aqua header for the end-reward section
	private static final int REWARD_TEXT_COLOR = 0xFFAAFFFF;
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
		if (config.showTimer) lines.add(new Line(DungeonStatsClient.timer().hudText(), TIMER_COLOR));
		if (config.showMobCounter) lines.add(new Line("Mobs killed: " + DungeonStatsClient.kills().kills(), KILLS_COLOR));

		// Normal chests ("Added ... to the loot pool")
		lines.add(new Line("Chests looted: " + level.chestsLooted, TEXT_COLOR));
		config.displayLoot(level.loot).forEach((name, amount) ->
				lines.add(new Line("  " + name + ": " + amount, TEXT_COLOR))); // embers folded into dragon eggs

		// End-of-dungeon rewards ("Collected ... from the loot pool")
		lines.add(new Line("End rewards claimed: " + level.rewardsClaimed, REWARD_COLOR));
		config.displayLoot(level.rewardLoot).forEach((name, amount) ->
				lines.add(new Line("  " + name + ": " + amount, REWARD_TEXT_COLOR)));

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

	private record Line(String text, int color) {
	}
}
