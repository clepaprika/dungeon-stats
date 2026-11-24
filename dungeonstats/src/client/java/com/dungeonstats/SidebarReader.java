package com.dungeonstats;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;

/**
 * Reads the server's scoreboard sidebar (works even when the sidebar is hidden with /dstats sidebar).
 *
 * The "Players:" line shows one face per slot: green = a player in the dungeon, grey = empty slot.
 * The number of green faces is used to split the server's chest total ("Progress: 22/66") between players.
 * It's read once per run, 5 seconds after "You have entered ... Dungeon #N" (not again when you re-enter the same
 * run with /dungeon return, since dead teammates' faces turn orange then red).
 */
public class SidebarReader {
	/** Wait this long after entering a dungeon before reading (lets the sidebar update): 5 seconds. */
	private static final int FIRST_READ_DELAY_TICKS = 100;
	/** If there's no "Players:" line yet, try again every second, at most this many times. */
	private static final int MAX_RETRIES = 30;

	/** Players in the dungeon, read once per dungeon (at least 1). */
	private int players = 1;
	/** Ticks until the next read, or -1 when no read is planned. */
	private int ticksUntilRead = -1;
	private int retriesLeft = 0;

	public int players() {
		return players;
	}

	/** Called when you enter a dungeon: the player count is read once, a few seconds later. */
	public void scheduleRead() {
		ticksUntilRead = FIRST_READ_DELAY_TICKS;
		retriesLeft = MAX_RETRIES;
	}

	public void onClientTick(Minecraft client) {
		if (ticksUntilRead < 0 || client.level == null) return;
		if (--ticksUntilRead > 0) return;

		Integer count = null;
		try {
			count = countPlayers(sidebarLines(client), null);
		} catch (RuntimeException e) {
			DungeonStatsClient.LOGGER.warn("Could not read the scoreboard sidebar", e); // never break the game
		}

		if (count != null) {
			players = Math.max(1, count);
			ticksUntilRead = -1; // done for this dungeon
			DungeonStatsClient.LOGGER.info("Players in dungeon: {}", players);
		} else if (retriesLeft-- > 0) {
			ticksUntilRead = 20; // no "Players:" line yet: try again in a second
		} else {
			players = 1;
			ticksUntilRead = -1;
		}
	}

	/** The sidebar lines as shown on screen (team prefix + name + suffix), top to bottom order not guaranteed. */
	static List<Component> sidebarLines(Minecraft client) {
		List<Component> lines = new ArrayList<>();
		if (client.level == null) return lines;
		Scoreboard scoreboard = client.level.getScoreboard();
		Objective objective = scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR);
		if (objective == null) return lines;

		lines.add(objective.getDisplayName());
		for (PlayerScoreEntry entry : scoreboard.listPlayerScores(objective)) {
			if (entry.isHidden()) continue;
			PlayerTeam team = scoreboard.getPlayersTeam(entry.owner());
			lines.add(PlayerTeam.formatNameForTeam(team, entry.ownerName()));
		}
		return lines;
	}

	/** One glyph after "Players:", with its colour (null if the text has no colour). */
	record Glyph(int codePoint, TextColor color) {
	}

	/**
	 * Counts the green faces after "Players:". Returns null if there's no such line.
	 *
	 * Faces in different text colours: green-ish ones count. Faces all in the same colour (or none, e.g.
	 * coloured images from a resource pack): the faces drawn with the same character as the first one count,
	 * since you're always in your own dungeon and the green faces come first.
	 *
	 * @param debug if not null, receives a description of what was found
	 */
	static Integer countPlayers(List<Component> lines, List<String> debug) {
		for (Component line : lines) {
			List<Glyph> glyphs = new ArrayList<>();
			StringBuilder plain = new StringBuilder();
			boolean[] afterLabel = {false};

			line.visit((Style style, String text) -> {
				text.codePoints().forEach(cp -> {
					plain.appendCodePoint(cp);
					if (!afterLabel[0]) {
						if (plain.toString().toLowerCase(java.util.Locale.ROOT).endsWith("players:")) afterLabel[0] = true;
						return;
					}
					if (!Character.isWhitespace(cp) && !Character.isISOControl(cp)) {
						glyphs.add(new Glyph(cp, style.getColor()));
					}
				});
				return Optional.empty();
			}, Style.EMPTY);

			if (!afterLabel[0]) continue;

			if (debug != null) {
				debug.add("Players line: \"" + plain + "\"");
				for (Glyph g : glyphs) {
					debug.add("  U+" + Integer.toHexString(g.codePoint()).toUpperCase(java.util.Locale.ROOT)
							+ (g.color() != null ? " colour #" + String.format("%06X", g.color().getValue() & 0xFFFFFF) : " no colour"));
				}
			}
			if (glyphs.isEmpty()) return 1;

			// Use colours only if the faces actually differ in colour (a line coloured all white tells us nothing).
			long distinctColours = glyphs.stream().map(g -> g.color() == null ? -1 : g.color().getValue()).distinct().count();
			boolean useColour = distinctColours > 1;
			int count = 0;
			if (useColour) {
				for (Glyph g : glyphs) {
					if (g.color() != null && isGreen(g.color().getValue())) count++;
				}
			} else {
				int first = glyphs.get(0).codePoint();
				for (Glyph g : glyphs) {
					if (g.codePoint() == first) count++;
				}
			}
			if (debug != null) debug.add("  -> " + Math.max(1, count) + " player(s)");
			return Math.max(1, count);
		}
		if (debug != null) debug.add("No \"Players:\" line found in the sidebar.");
		return null;
	}

	private static boolean isGreen(int rgb) {
		int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
		return g > 120 && g > r + 40 && g > b + 40;
	}
}
