package com.dungeonstats;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/**
 * User-editable settings, stored in config/dungeonstats/config.json.
 * Each loot category matches items whose display name contains one of the "match" words
 * (case-insensitive). Edit the file and run /dstats reload to apply changes.
 */
public class ModConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	public static class Category {
		public String name;
		public List<String> match = new ArrayList<>();

		public Category() {
		}

		Category(String name, String... match) {
			this.name = name;
			this.match = new ArrayList<>(List.of(match));
		}

		/**
		 * Optional crafting conversion for display, e.g. 4 embers craft 1 dragon egg:
		 * unitName = "ember", craftCost = 4, craftInto = "dragon egg": 3 eggs + 13 embers are shown as
		 * "Dragon Eggs: 6, Embers: 1". Leave craftInto empty (or craftCost below 2) to show plain numbers.
		 */
		public String unitName = null;
		public int craftCost = 0;
		public String craftInto = null;

		Category crafts(String unitName, int craftCost, String craftInto) {
			this.unitName = unitName;
			this.craftCost = craftCost;
			this.craftInto = craftInto;
			return this;
		}

		public boolean matches(String displayName) {
			String lower = displayName.toLowerCase(Locale.ROOT);
			for (String m : match) {
				if (!m.isEmpty() && lower.contains(m.toLowerCase(Locale.ROOT))) {
					return true;
				}
			}
			return false;
		}
	}

	/**
	 * For display only: category name -> amount, with craftable categories folded into what they craft.
	 * {Dragon Eggs=4, Embers=38} -> {Dragon Eggs=13, Embers=2}. The saved numbers aren't changed.
	 */
	public java.util.Map<String, Integer> displayLoot(java.util.Map<String, Integer> loot) {
		java.util.Map<String, Integer> shown = new java.util.LinkedHashMap<>();
		for (Category category : categories) shown.put(category.name, loot.getOrDefault(category.name, 0));

		for (Category category : categories) {
			if (category.craftInto == null || category.craftInto.isBlank() || category.craftCost < 2) continue;
			// The category the crafted item belongs to, e.g. "dragon egg" -> "Dragon Eggs".
			Category target = null;
			for (Category other : categories) {
				if (other != category && other.matches(category.craftInto)) target = other;
			}
			if (target == null) continue;

			int units = shown.get(category.name);
			shown.merge(target.name, units / category.craftCost, Integer::sum);
			shown.put(category.name, units % category.craftCost);
		}
		return shown;
	}

	/** Bumped when new defaults need adding to existing config files (0 = file from before versions existed). */
	public int configVersion = 0;
	private static final int CURRENT_VERSION = 3;

	/** Loot categories shown in the HUD, in this order. */
	public List<Category> categories = new ArrayList<>(List.of(
			new Category("Dragon Eggs", "dragon egg"),
			new Category("Embers", "ember").crafts("ember", 4, "dragon egg"),
			new Category("Star Remnants", "star remnant")
	));

	/**
	 * Chat message that means you entered a dungeon. Group 1 = dungeon name, group 2 = dungeon number.
	 * Matches e.g. "You have entered Prisma Dungeon #16". Set autoDetectLevel to false to set levels by hand only.
	 */
	public boolean autoDetectLevel = true;
	public String enterDungeonPattern = "^You have entered (.+?) #(\\d+)";

	/** Chat message for a collected chest. Group 1 = comma-separated item list. */
	public String lootPoolPattern = "^Added (.+?) to the loot pool";

	/** A player was kicked out of the dungeon. Group 1 = player name. If it's you, the run in progress is deleted. */
	public String livesLostPattern = "^(\\S+) lost all of their lives";

	/** Dungeon finished. Group 1 = completion time in seconds. Stops the timer and shows this time. */
	public String completedPattern = "^Dungeon completed in ([0-9.,]+) seconds";

	/** "Fastest Times" item: header (group 1 = dungeon name, group 2 = number), entries (rank, player, time), length. */
	public String leaderboardHeaderPattern = "^For (.+?) #(\\d+)$";
	public String leaderboardEntryPattern = "^#(\\d+)\\.\\s*(.+?):\\s*([0-9:.]+)";
	public String leaderboardLengthPattern = "^Dungeon Length:\\s*(.+)$";

	/** "clepaprika completed Prisma Dungeon #24 in 724.6 seconds!" Groups: player, dungeon name, number, seconds.
	 *  If the player is you, your time is compared with that dungeon's saved leaderboard. */
	public String playerCompletedPattern = "^(.+?) completed (.+?) #(\\d+) in ([0-9.,]+) seconds";

	/** End-of-dungeon reward chest claim. Group 1 = comma-separated item list (e.g. "3 x Dragon Eggs, Star Remnant"). */
	public String rewardPattern = "^Collected (.+?) from the loot pool";

	/** HUD placement. hudCorner: top_left, top_right, bottom_left or bottom_right.
	 *  hudX / hudY: distance in pixels from that corner. hudScale: 1.0 = normal size (0.25 to 4). */
	public String hudCorner = "top_left";
	/** Extra HUD lines that can be switched off in /dstats settings. */
	public boolean showTimer = true;
	public boolean showMobCounter = true;
	public int hudX = 4;
	public int hudY = 4;
	public float hudScale = 1.0f;

	public static final java.util.List<String> CORNERS = java.util.List.of("top_left", "top_right", "bottom_left", "bottom_right");
	public static final float MIN_SCALE = 0.25f;
	public static final float MAX_SCALE = 4.0f;

	public float clampedScale() {
		return Math.max(MIN_SCALE, Math.min(MAX_SCALE, hudScale));
	}

	public static ModConfig load(Path file) {
		if (Files.exists(file)) {
			try (Reader reader = Files.newBufferedReader(file)) {
				ModConfig config = GSON.fromJson(reader, ModConfig.class);
				if (config != null && config.categories != null) {
					config.upgrade();
					config.save(file); // writes any settings added in newer versions of the mod
					return config;
				}
			} catch (Exception e) {
				DungeonStatsClient.LOGGER.error("Could not read {}, using defaults", file, e);
			}
		}

		ModConfig config = new ModConfig();
		config.configVersion = CURRENT_VERSION;
		config.save(file);
		return config;
	}

	/** Adds defaults introduced after this config file was created. */
	private void upgrade() {
		if (configVersion < 2) {
			// v2: 4 embers craft 1 dragon egg
			for (Category category : categories) {
				boolean isEmbers = category.match != null && category.match.stream().anyMatch(m -> m.equalsIgnoreCase("ember"));
				if (isEmbers && category.craftInto == null) category.crafts("ember", 4, "dragon egg");
			}
		}
		if (configVersion < 3) {
			// v3: completion and leaderboard lines can list several players ("clepaprika, Hyunn_W0o completed ...")
			if ("^(\\S+) completed (.+?) #(\\d+) in ([0-9.,]+) seconds".equals(playerCompletedPattern)) {
				playerCompletedPattern = "^(.+?) completed (.+?) #(\\d+) in ([0-9.,]+) seconds";
			}
			if ("^#(\\d+)\\.\\s*(\\S+?):\\s*([0-9:.]+)".equals(leaderboardEntryPattern)) {
				leaderboardEntryPattern = "^#(\\d+)\\.\\s*(.+?):\\s*([0-9:.]+)";
			}
		}
		configVersion = CURRENT_VERSION;
	}

	public void save(Path file) {
		try {
			Files.createDirectories(file.getParent());
			try (Writer writer = Files.newBufferedWriter(file)) {
				GSON.toJson(this, writer);
			}
		} catch (IOException e) {
			DungeonStatsClient.LOGGER.error("Could not write {}", file, e);
		}
	}
}
