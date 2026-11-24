package com.dungeonstats;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * Drag the HUD box with the mouse to place it. The position is stored relative to the HUD corner
 * chosen in the settings, so it stays in place when the window is resized.
 */
public class HudPositionScreen extends Screen {
	private final Screen parent;

	/** Mouse position from the last frame (used instead of the click event's own coordinates). */
	private double mouseX, mouseY;
	private StatsHud.Bounds bounds;
	private boolean dragging = false;
	private double grabOffsetX, grabOffsetY;

	public HudPositionScreen(Screen parent) {
		super(Component.literal("Drag the box to move it"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		addRenderableWidget(Button.builder(Component.literal("Reset position"), b -> {
			ModConfig config = DungeonStatsClient.config();
			config.hudX = 4;
			config.hudY = 4;
			DungeonStatsClient.saveConfig();
		}).bounds(this.width / 2 - 154, this.height - 28, 150, 20).build());

		addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
				.bounds(this.width / 2 + 4, this.height - 28, 150, 20).build());
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		super.extractRenderState(graphics, mouseX, mouseY, delta);
		this.mouseX = mouseX;
		this.mouseY = mouseY;

		if (dragging && bounds != null) {
			moveTo(mouseX - grabOffsetX, mouseY - grabOffsetY, bounds.width(), bounds.height());
		}

		bounds = StatsHud.draw(graphics);

		// Outline around the box: yellow while dragging or hovered, white otherwise.
		int color = dragging || bounds.contains(mouseX, mouseY) ? 0xFFFFFF55 : 0xFFFFFFFF;
		int l = (int) bounds.left() - 1, t = (int) bounds.top() - 1;
		int r = (int) (bounds.left() + bounds.width()) + 1, b = (int) (bounds.top() + bounds.height()) + 1;
		graphics.fill(l, t, r, t + 1, color);
		graphics.fill(l, b - 1, r, b, color);
		graphics.fill(l, t, l + 1, b, color);
		graphics.fill(r - 1, t, r, b, color);

		String title = this.title.getString();
		graphics.text(this.font, title, (this.width - this.font.width(title)) / 2, this.height / 2 - 4, 0xFFFFFFFF, true);
	}

	/** Puts the box's top-left corner at (left, top) on screen, converted to the configured corner. */
	private void moveTo(double left, double top, float boxWidth, float boxHeight) {
		ModConfig config = DungeonStatsClient.config();
		left = Math.max(0, Math.min(this.width - boxWidth, left));
		top = Math.max(0, Math.min(this.height - boxHeight, top));

		boolean right = config.hudCorner != null && config.hudCorner.endsWith("right");
		boolean bottom = config.hudCorner != null && config.hudCorner.startsWith("bottom");
		config.hudX = (int) Math.round(right ? this.width - left - boxWidth : left);
		config.hudY = (int) Math.round(bottom ? this.height - top - boxHeight : top);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (super.mouseClicked(event, doubleClick)) return true; // a button was clicked
		if (bounds != null && bounds.contains(mouseX, mouseY)) {
			dragging = true;
			grabOffsetX = mouseX - bounds.left();
			grabOffsetY = mouseY - bounds.top();
			return true;
		}
		return false;
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		if (dragging) {
			dragging = false;
			DungeonStatsClient.saveConfig();
			return true;
		}
		return super.mouseReleased(event);
	}

	@Override
	public void onClose() {
		if (dragging) DungeonStatsClient.saveConfig();
		this.minecraft.gui.setScreen(this.parent);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
