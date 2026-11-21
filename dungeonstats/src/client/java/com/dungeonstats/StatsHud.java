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
	private static final int REWARD_COLOR = 0xFF55FFFF; // aqua header for the end-reward section
	private static final int REWARD_TEXT_COLOR = 0xFFAAFFFF;
	private static final int BACKGROUND = 0x80000000; // half-transparent black

	private static final StatsData.Tally EMPTY = new StatsData.Tally();

	private StatsHud() {
	}

	public static void extract(GuiGraphicsExtractor graphics, DeltaTracker tickCounter) {
		StatsData stats = DungeonStatsClient.stats();
		Minecraft client = Minecraft.getInstance();
		if (!stats.hudVisible || client.player == null) return;

		Font font = client.font;
		// Show the current run (the dungeon you're in, or the last one you did).
		// Empty counters if no run has started yet, without creating one just for drawing.
		StatsData.Tally level = stats.lastRun != null && stats.lastRun.level == stats.currentLevel
				? stats.lastRun : EMPTY;

		List<Line> lines = new ArrayList<>();
		lines.add(new Line(stats.label(stats.currentLevel) + (stats.tracking ? "" : " (paused)"),
				stats.tracking ? TITLE_COLOR : PAUSED_COLOR));

		// Normal chests ("Added ... to the loot pool")
		lines.add(new Line("Chests looted: " + level.chestsLooted, TEXT_COLOR));
		for (ModConfig.Category category : DungeonStatsClient.config().categories) {
			lines.add(new Line("  " + category.name + ": " + level.getLoot(category.name), TEXT_COLOR));
		}

		// End-of-dungeon rewards ("Collected ... from the loot pool")
		lines.add(new Line("End rewards claimed: " + level.rewardsClaimed, REWARD_COLOR));
		for (ModConfig.Category category : DungeonStatsClient.config().categories) {
			lines.add(new Line("  " + category.name + ": " + level.getRewardLoot(category.name), REWARD_TEXT_COLOR));
		}

		int width = 0;
		for (Line line : lines) width = Math.max(width, font.width(line.text()));

		int lineHeight = font.lineHeight + 1;
		int padding = 2;
		// Size of the box at normal (1.0) scale, padding included.
		int boxWidth = width + padding * 2;
		int boxHeight = lineHeight * lines.size() + padding * 2 - 1;

		// Where the box goes on screen, after scaling, measured from the chosen corner.
		ModConfig config = DungeonStatsClient.config();
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
	}

	private record Line(String text, int color) {
	}
}
