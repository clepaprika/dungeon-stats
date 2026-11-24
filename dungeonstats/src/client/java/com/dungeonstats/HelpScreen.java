package com.dungeonstats;

import java.util.List;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Short explanation of the mod and its commands. */
public class HelpScreen extends Screen {
	private static final List<String> LINES = List.of(
			"§6How it works",
			"Entering a dungeon starts a run. Chests (\"Added ... to the loot pool\") and",
			"end rewards (\"Collected ... from the loot pool\") are counted automatically.",
			"A run is saved when you collect the end loot, and deleted if you lose all lives.",
			"",
			"§6Commands",
			"§e/dstats§r  last dungeon + all-time totals",
			"§e/dstats <number>§r  one dungeon: chests per run, totals, leaderboard",
			"§e/dstats levels§r  every dungeon, summed",
			"§e/dstats items [all|<number>]§r  every item collected",
			"§e/dstats settings§r  this menu   §e/dstats help§r  all commands in chat",
			"§e/dstats pause | resume§r  stop / restart counting",
			"§e/dstats reset [<number>]§r  clear data",
			"",
			"§6Keys§r (Options > Controls > Key Binds > Dungeon Stats)",
			"§eJ§r show/hide HUD   §eK§r pause tracking   §ePage Up/Down§r change level",
			"",
			"§6Leaderboards",
			"Open the server's Fastest Times menu to save leaderboards. Your completion",
			"time is then compared with them automatically."
	);

	private final Screen parent;

	public HelpScreen(Screen parent) {
		super(Component.literal("Dungeon Stats Help"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		addRenderableWidget(Button.builder(Component.literal("Back"), b -> onClose())
				.bounds(this.width / 2 - 100, this.height - 28, 200, 20).build());
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		super.extractRenderState(graphics, mouseX, mouseY, delta);
		String title = this.title.getString();
		graphics.text(this.font, title, (this.width - this.font.width(title)) / 2, 15, 0xFFFFFFFF, true);

		int y = 35;
		int lineHeight = this.font.lineHeight + 2;
		for (String line : LINES) {
			int x = (this.width - this.font.width(stripCodes(line))) / 2;
			graphics.text(this.font, line, x, y, 0xFFFFFFFF, true);
			y += lineHeight;
		}
	}

	private static String stripCodes(String s) {
		return s.replaceAll("§.", "");
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
