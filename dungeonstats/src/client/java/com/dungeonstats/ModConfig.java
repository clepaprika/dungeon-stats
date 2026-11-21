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

	/** Loot categories shown in the HUD, in this order. */
	public List<Category> categories = new ArrayList<>(List.of(
			new Category("Dragon Eggs", "dragon egg"),
			new Category("Embers", "ember"),
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

	/** End-of-dungeon reward chest claim. Group 1 = comma-separated item list (e.g. "3 x Dragon Eggs, Star Remnant"). */
	public String rewardPattern = "^Collected (.+?) from the loot pool";

	/** HUD placement. hudCorner: top_left, top_right, bottom_left or bottom_right.
	 *  hudX / hudY: distance in pixels from that corner. hudScale: 1.0 = normal size (0.25 to 4). */
	public String hudCorner = "top_left";
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
					config.save(file); // writes any settings added in newer versions of the mod
					return config;
				}
			} catch (Exception e) {
				DungeonStatsClient.LOGGER.error("Could not read {}, using defaults", file, e);
			}
		}

		ModConfig config = new ModConfig();
		config.save(file);
		return config;
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
