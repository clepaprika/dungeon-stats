package com.dungeonstats;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * Compares your completion time ("clepaprika completed Prisma Dungeon #24 in 724.6 seconds!") with the
 * saved "Fastest Times" leaderboard of that dungeon, and builds the chat lines that say how you did.
 *
 * The run's time is compared with every time on the leaderboard (yours included, as shown in the menu).
 * If you're on it, an extra line says whether this run beat your previous time.
 */
public final class LeaderboardCompare {
	private LeaderboardCompare() {
	}

	public static List<Component> compare(String me, int dungeon, String dungeonName, double mySeconds) {
		List<Component> out = new ArrayList<>();
		String label = dungeonName + " #" + dungeon;

		StatsData.Leaderboard board = DungeonStatsClient.stats().leaderboards.get(dungeon);
		if (board == null || board.entries == null || board.entries.isEmpty()) {
			out.add(prefix().append(Component.literal("No leaderboard saved for " + label
					+ ". Open its Fastest Times menu so your next run can be compared.").withStyle(ChatFormatting.GRAY)));
			return out;
		}

		// All leaderboard times, fastest first, plus your own previous time if you're on it.
		List<StatsData.LeaderboardEntry> entries = new ArrayList<>();
		Double myOldBest = null;
		String myOldBestText = null;
		for (StatsData.LeaderboardEntry e : board.entries) {
			Double t = parseTime(e.time);
			if (t == null) continue;
			entries.add(e);
			if (e.player.equalsIgnoreCase(me)) {
				myOldBest = t;
				myOldBestText = e.time;
			}
		}
		entries.sort((a, b) -> Double.compare(parseTime(a.time), parseTime(b.time)));

		String myTime = formatTime(mySeconds);

		if (entries.isEmpty()) {
			out.add(prefix().append(green("You'd be #1 on " + label + " with " + myTime + "!")));
		} else {
			StatsData.LeaderboardEntry first = entries.get(0);
			double firstTime = parseTime(first.time);

			// Where this run's time would land: 1 + how many leaderboard times are faster.
			int place = 1;
			for (StatsData.LeaderboardEntry e : entries) {
				if (parseTime(e.time) <= mySeconds) place++;
			}
			int slots = Math.max(5, entries.size());

			if (place == 1) {
				// 1. Fastest time beaten.
				String whose = first.player.equalsIgnoreCase(me) ? "your own #1 time" : "#1 " + first.player;
				out.add(prefix().append(green("NEW FASTEST TIME on " + label + "! " + myTime + ", ")
						.append(gold(formatDiff(firstTime - mySeconds)))
						.append(green(" faster than " + whose + " (" + first.time + ")."))));
			} else if (place <= slots) {
				// 2. Another leaderboard time beaten (or an empty spot taken).
				MutableComponent line = green("Your time " + myTime + " would be #" + place + " on " + label);
				if (place - 1 < entries.size()) {
					StatsData.LeaderboardEntry beaten = entries.get(place - 1);
					String whose = beaten.player.equalsIgnoreCase(me) ? "your own time" : beaten.player;
					line.append(green(", beating " + whose + " (" + beaten.time + ")"));
				}
				out.add(prefix().append(line.append(green("."))));
				out.add(prefix().append(yellow(formatDiff(mySeconds - firstTime) + " behind #1 " + first.player
						+ " (" + first.time + ").")));
			} else {
				// 3. Slower than the 5th time.
				StatsData.LeaderboardEntry last = entries.get(entries.size() - 1);
				out.add(prefix().append(red("Not on the leaderboard: " + myTime + " is "
						+ formatDiff(mySeconds - parseTime(last.time)) + " slower than #" + last.rank + " "
						+ last.player + " (" + last.time + ").")));
			}
		}

		// Your own previous time on the leaderboard, if you're on it.
		if (myOldBest != null) {
			if (mySeconds < myOldBest) {
				out.add(prefix().append(green("New personal best! " + formatDiff(myOldBest - mySeconds)
						+ " faster than your leaderboard time (" + myOldBestText + ").")));
			} else {
				out.add(prefix().append(Component.literal("Your leaderboard time is still " + myOldBestText + " ("
						+ formatDiff(mySeconds - myOldBest) + " faster).").withStyle(ChatFormatting.GRAY)));
			}
		}

		out.add(prefix().append(Component.literal("Compared with the leaderboard saved "
				+ DungeonStatsClient.ago(board.updatedAt) + ".").withStyle(ChatFormatting.DARK_GRAY)));
		return out;
	}

	/** "1:22:41.44", "36:00.55" or "724.6" -> seconds. Null if it can't be read. */
	public static Double parseTime(String text) {
		if (text == null) return null;
		try {
			String[] parts = text.strip().replace(',', '.').split(":");
			double seconds = 0;
			for (String part : parts) {
				seconds = seconds * 60 + Double.parseDouble(part);
			}
			return seconds;
		} catch (NumberFormatException e) {
			return null;
		}
	}

	/** 4961.44 -> "1:22:41.44", 724.6 -> "12:04.60", 12.4 -> "12.40s" (same style as the leaderboard). */
	public static String formatTime(double seconds) {
		long hundredths = Math.round(Math.max(0, seconds) * 100);
		long h = hundredths / 360_000;
		long m = (hundredths / 6_000) % 60;
		long s = (hundredths / 100) % 60;
		long cs = hundredths % 100;
		if (h > 0) return String.format(Locale.ROOT, "%d:%02d:%02d.%02d", h, m, s, cs);
		if (m > 0) return String.format(Locale.ROOT, "%d:%02d.%02d", m, s, cs);
		return String.format(Locale.ROOT, "%d.%02ds", s, cs);
	}

	private static String formatDiff(double seconds) {
		return formatTime(Math.abs(seconds));
	}

	private static MutableComponent prefix() {
		return Component.literal("[Dungeon Stats] ").withStyle(ChatFormatting.GOLD);
	}

	private static MutableComponent green(String s) {
		return Component.literal(s).withStyle(ChatFormatting.GREEN);
	}

	private static MutableComponent yellow(String s) {
		return Component.literal(s).withStyle(ChatFormatting.YELLOW);
	}

	private static MutableComponent red(String s) {
		return Component.literal(s).withStyle(ChatFormatting.RED);
	}

	private static MutableComponent gold(String s) {
		return Component.literal(s).withStyle(ChatFormatting.GOLD);
	}
}
