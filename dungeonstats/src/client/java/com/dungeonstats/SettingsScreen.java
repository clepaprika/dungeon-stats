package com.dungeonstats;

import java.util.Locale;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * /dstats settings: buttons to customise the HUD.
 *
 *   MOB COUNTER: ON     HUD POSITION (drag the box)
 *   TIMER: ON           HUD CORNER: TOP LEFT
 *   [-] HUD SCALE [+]   SHOW HUD: ON
 *   RESET ALL DATA      TRACKING: ON
 *   HELP
 *                 DONE
 */
public class SettingsScreen extends Screen {
	private static final float[] SCALES = {0.25f, 0.5f, 0.6f, 0.75f, 0.85f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 2.5f, 3.0f, 4.0f};
	private static final int BUTTON_WIDTH = 150;
	private static final int BUTTON_HEIGHT = 20; // keep 20: button textures are made for it
	private static final int ROW = 24;

	private final Screen parent;

	public SettingsScreen(Screen parent) {
		super(Component.literal("Dungeon Stats Settings"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		ModConfig config = DungeonStatsClient.config();
		StatsData stats = DungeonStatsClient.stats();

		int leftX = this.width / 2 - BUTTON_WIDTH - 5;
		int rightX = this.width / 2 + 5;
		int y = 40;

		// --- Left column ---
		addRenderableWidget(Button.builder(onOff("Mob counter", config.showMobCounter), b -> {
			config.showMobCounter = !config.showMobCounter;
			DungeonStatsClient.saveConfig();
			b.setMessage(onOff("Mob counter", config.showMobCounter));
		}).bounds(leftX, y, BUTTON_WIDTH, BUTTON_HEIGHT).build());

		addRenderableWidget(Button.builder(onOff("Timer", config.showTimer), b -> {
			config.showTimer = !config.showTimer;
			DungeonStatsClient.saveConfig();
			b.setMessage(onOff("Timer", config.showTimer));
		}).bounds(leftX, y + ROW, BUTTON_WIDTH, BUTTON_HEIGHT).build());

		// HUD scale: [-] HUD scale: 1.00x [+]
		Button scaleLabel = Button.builder(scaleText(), b -> {
			config.hudScale = 1.0f; // clicking the label itself resets to normal size
			DungeonStatsClient.saveConfig();
			b.setMessage(scaleText());
		}).bounds(leftX + 22, y + ROW * 2, BUTTON_WIDTH - 44, BUTTON_HEIGHT).build();
		addRenderableWidget(Button.builder(Component.literal("-"), b -> changeScale(-1, scaleLabel))
				.bounds(leftX, y + ROW * 2, 20, BUTTON_HEIGHT).build());
		addRenderableWidget(scaleLabel);
		addRenderableWidget(Button.builder(Component.literal("+"), b -> changeScale(1, scaleLabel))
				.bounds(leftX + BUTTON_WIDTH - 20, y + ROW * 2, 20, BUTTON_HEIGHT).build());

		addRenderableWidget(Button.builder(Component.literal("Reset all data"), b ->
				this.minecraft.gui.setScreen(new ConfirmScreen(confirmed -> {
					if (confirmed) {
						DungeonStatsClient.resetAllData();
					}
					this.minecraft.gui.setScreen(this);
				}, Component.literal("Reset all Dungeon Stats data?"),
						Component.literal("This deletes every saved run, total, chest count and leaderboard. It can't be undone."))))
				.bounds(leftX, y + ROW * 3, BUTTON_WIDTH, BUTTON_HEIGHT).build());

		addRenderableWidget(Button.builder(Component.literal("Help"), b ->
				this.minecraft.gui.setScreen(new HelpScreen(this)))
				.bounds(leftX, y + ROW * 4, BUTTON_WIDTH, BUTTON_HEIGHT).build());

		// --- Right column ---
		addRenderableWidget(Button.builder(Component.literal("HUD position..."), b ->
				this.minecraft.gui.setScreen(new HudPositionScreen(this)))
				.bounds(rightX, y, BUTTON_WIDTH, BUTTON_HEIGHT).build());

		addRenderableWidget(Button.builder(cornerText(), b -> {
			int i = ModConfig.CORNERS.indexOf(config.hudCorner);
			config.hudCorner = ModConfig.CORNERS.get((i + 1) % ModConfig.CORNERS.size());
			DungeonStatsClient.saveConfig();
			b.setMessage(cornerText());
		}).bounds(rightX, y + ROW, BUTTON_WIDTH, BUTTON_HEIGHT).build());

		addRenderableWidget(Button.builder(onOff("Show HUD", stats.hudVisible), b -> {
			stats.hudVisible = !stats.hudVisible;
			DungeonStatsClient.saveStats();
			b.setMessage(onOff("Show HUD", stats.hudVisible));
		}).bounds(rightX, y + ROW * 2, BUTTON_WIDTH, BUTTON_HEIGHT).build());

		addRenderableWidget(Button.builder(onOff("Tracking", stats.tracking), b -> {
			stats.tracking = !stats.tracking;
			DungeonStatsClient.saveStats();
			b.setMessage(onOff("Tracking", stats.tracking));
		}).bounds(rightX, y + ROW * 3, BUTTON_WIDTH, BUTTON_HEIGHT).build());

		// --- Bottom ---
		addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
				.bounds(this.width / 2 - 100, this.height - 28, 200, BUTTON_HEIGHT).build());
	}

	private void changeScale(int direction, Button label) {
		ModConfig config = DungeonStatsClient.config();
		float current = config.clampedScale();
		float next = current;
		if (direction > 0) {
			for (float s : SCALES) if (s > current + 0.001f) { next = s; break; }
		} else {
			for (int i = SCALES.length - 1; i >= 0; i--) if (SCALES[i] < current - 0.001f) { next = SCALES[i]; break; }
		}
		config.hudScale = next;
		DungeonStatsClient.saveConfig();
		label.setMessage(scaleText());
	}

	private static Component onOff(String name, boolean on) {
		return Component.literal(name + ": " + (on ? "ON" : "OFF"));
	}

	private static Component scaleText() {
		return Component.literal(String.format(Locale.ROOT, "HUD scale: %.2fx", DungeonStatsClient.config().clampedScale()));
	}

	private static Component cornerText() {
		String corner = DungeonStatsClient.config().hudCorner == null ? "top_left" : DungeonStatsClient.config().hudCorner;
		return Component.literal("HUD corner: " + corner.replace('_', ' '));
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		super.extractRenderState(graphics, mouseX, mouseY, delta);
		String title = this.title.getString();
		graphics.text(this.font, title, (this.width - this.font.width(title)) / 2, 15, 0xFFFFFFFF, true);

		// Preview of the HUD, so changes are visible right away.
		StatsHud.draw(graphics);
	}

	@Override
	public void onClose() {
		this.minecraft.gui.setScreen(this.parent);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
