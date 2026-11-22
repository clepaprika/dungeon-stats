package com.dungeonstats;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

/**
 * Reads the server's "Fastest Times" item when you have its menu open:
 * <pre>
 *   For Prisma Boss Dungeon #50
 *   #1. DarkHabits: 1:22:41.44
 *   #2. clepaprika: 1:33:58.24
 *   ...
 *   Dungeon Length: 3h
 * </pre>
 * Each leaderboard you look at is saved under its dungeon number and shown by /dstats &lt;number&gt;.
 * Clicking "next dungeon" in the menu changes the item, so every dungeon you click through gets saved.
 */
public class LeaderboardReader {
	private static final int SCAN_EVERY_TICKS = 10;

	/** Everything Minecraft's "§" formatting codes look like, in case the server puts them in the text. */
	private static final Pattern FORMATTING = Pattern.compile("§.");

	private int ticks = 0;

	public void onClientTick(Minecraft client) {
		if (client.player == null || ++ticks < SCAN_EVERY_TICKS) return;
		ticks = 0;

		AbstractContainerMenu menu = client.player.containerMenu;
		if (menu == null || menu == client.player.inventoryMenu) return; // only while a menu (chest GUI) is open

		for (Slot slot : menu.slots) {
			ItemStack stack = slot.getItem();
			if (stack.isEmpty()) continue;
			ItemLore lore = stack.get(DataComponents.LORE);
			if (lore == null || lore.lines().isEmpty()) continue;

			List<String> lines = new ArrayList<>();
			lines.add(clean(stack.getHoverName()));
			for (Component line : lore.lines()) lines.add(clean(line));
			read(lines);
		}
	}

	private static String clean(Component component) {
		return FORMATTING.matcher(component.getString()).replaceAll("").strip();
	}

	/** Looks for a leaderboard in these tooltip lines and saves it if found. */
	private static void read(List<String> lines) {
		ModConfig config = DungeonStatsClient.config();
		Pattern headerPattern = compile(config.leaderboardHeaderPattern);
		Pattern entryPattern = compile(config.leaderboardEntryPattern);
		Pattern lengthPattern = compile(config.leaderboardLengthPattern);
		if (headerPattern == null || entryPattern == null) return;

		String dungeonName = null;
		int dungeonNumber = -1;
		String length = null;
		List<StatsData.LeaderboardEntry> entries = new ArrayList<>();

		for (String line : lines) {
			if (dungeonNumber < 0) {
				Matcher header = headerPattern.matcher(line);
				if (header.find()) {
					try {
						dungeonNumber = Integer.parseInt(header.group(2));
						dungeonName = header.group(1).strip();
					} catch (RuntimeException ignored) {
					}
				}
				continue;
			}

			Matcher entry = entryPattern.matcher(line);
			if (entry.find()) {
				StatsData.LeaderboardEntry e = new StatsData.LeaderboardEntry();
				e.rank = Integer.parseInt(entry.group(1));
				e.player = entry.group(2).strip();
				e.time = entry.group(3).strip();
				entries.add(e);
				continue;
			}

			if (lengthPattern != null) {
				Matcher len = lengthPattern.matcher(line);
				if (len.find()) length = len.group(1).strip();
			}
		}

		if (dungeonNumber < 0 || entries.isEmpty()) return;

		StatsData stats = DungeonStatsClient.stats();
		StatsData.Leaderboard old = stats.leaderboards.get(dungeonNumber);
		if (old != null && old.sameAs(dungeonName, entries, length)) return; // nothing new: don't rewrite the file

		StatsData.Leaderboard board = new StatsData.Leaderboard();
		board.dungeonName = dungeonName;
		board.entries = entries;
		board.length = length;
		board.updatedAt = System.currentTimeMillis();
		stats.leaderboards.put(dungeonNumber, board);
		DungeonStatsClient.saveStats();
		DungeonStatsClient.LOGGER.info("Saved leaderboard for {} #{} ({} entries)", dungeonName, dungeonNumber, entries.size());
	}

	private static final java.util.Map<String, Pattern> CACHE = new java.util.HashMap<>();

	private static Pattern compile(String regex) {
		if (regex == null || regex.isEmpty()) return null;
		return CACHE.computeIfAbsent(regex, r -> {
			try {
				return Pattern.compile(r, Pattern.CASE_INSENSITIVE);
			} catch (RuntimeException e) {
				DungeonStatsClient.LOGGER.error("Invalid pattern in config.json: {}", r, e);
				return null;
			}
		});
	}
}
