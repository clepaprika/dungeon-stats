package com.dungeonstats;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/**
 * Everything the mod remembers between game sessions, stored in config/dungeonstats/stats.json.
 *
 * The same counters are kept for:
 * <ul>
 *   <li>{@link #currentRun}: the run in progress. Kept in memory only, never written to disk.</li>
 *   <li>{@link #lastRun}: the last finished run (end loot collected)</li>
 *   <li>{@link #levels}: per dungeon number, summed over all finished runs of it</li>
 *   <li>{@link #total}: every finished dungeon, summed</li>
 * </ul>
 * A run only counts once its end loot is collected (first "Collected ... from the loot pool" message).
 * Runs you leave before that are thrown away.
 */
public class StatsData {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	/** Counters for chests, end rewards and the items found in them. */
	public static class Tally {
		public int chestsLooted = 0;
		/** Category name (Dragon Eggs, ...) -> amount, from normal chests. */
		public Map<String, Integer> loot = new LinkedHashMap<>();
		/** Every item from normal chests -> amount (for /dstats items). */
		public Map<String, Integer> items = new LinkedHashMap<>();

		/** End-of-dungeon reward chests ("Collected ... from the loot pool"), kept separate from normal chests. */
		public int rewardsClaimed = 0;
		public Map<String, Integer> rewardLoot = new LinkedHashMap<>();
		public Map<String, Integer> rewardItems = new LinkedHashMap<>();

		public int getLoot(String category) {
			return loot.getOrDefault(category, 0);
		}

		public int getRewardLoot(String category) {
			return rewardLoot.getOrDefault(category, 0);
		}

		void addAll(Tally other) {
			chestsLooted += other.chestsLooted;
			rewardsClaimed += other.rewardsClaimed;
			other.loot.forEach((k, v) -> loot.merge(k, v, Integer::sum));
			other.items.forEach((k, v) -> items.merge(k, v, Integer::sum));
			other.rewardLoot.forEach((k, v) -> rewardLoot.merge(k, v, Integer::sum));
			other.rewardItems.forEach((k, v) -> rewardItems.merge(k, v, Integer::sum));
		}
	}

	/** Totals for one dungeon number over all your runs of it. */
	public static class LevelStats extends Tally {
		/** Dungeon name as shown by the server, e.g. "Prisma Dungeon" (null if never auto-detected). */
		public String name = null;
		public int runs = 0;
		/** Chests looted in each run of this dungeon, oldest first (double chests count as 2). */
		public List<Integer> chestsPerRun = new ArrayList<>();

		/** Most chests looted in one run: the best guess for how many chests this dungeon has. */
		public int mostChestsInRun() {
			int most = 0;
			for (int c : chestsPerRun) most = Math.max(most, c);
			return most;
		}

		/** Average chests per run, ignoring runs where no chest was looted (e.g. left right away). */
		public double averageChestsPerRun() {
			int sum = 0, count = 0;
			for (int c : chestsPerRun) {
				if (c > 0) {
					sum += c;
					count++;
				}
			}
			return count == 0 ? 0 : (double) sum / count;
		}
	}

	/** One single trip into a dungeon. */
	public static class RunStats extends Tally {
		public int level;
		public String name;
		/** When the run started (milliseconds since 1970). */
		public long startedAt;
		/** True once the end loot was collected and the run was added to the saved stats. */
		public boolean completed = false;
	}

	/** One line of a "Fastest Times" leaderboard. */
	public static class LeaderboardEntry {
		public int rank;
		public String player;
		public String time;
	}

	/** A dungeon's "Fastest Times" leaderboard, as last seen in the server's menu. */
	public static class Leaderboard {
		public String dungeonName;
		public List<LeaderboardEntry> entries = new ArrayList<>();
		/** e.g. "3h" from "Dungeon Length: 3h" (null if not shown). */
		public String length;
		/** When it was read (milliseconds since 1970). */
		public long updatedAt;

		boolean sameAs(String name, List<LeaderboardEntry> other, String otherLength) {
			if (!Objects.equals(dungeonName, name) || !Objects.equals(length, otherLength)) return false;
			if (entries == null || entries.size() != other.size()) return false;
			for (int i = 0; i < other.size(); i++) {
				LeaderboardEntry a = entries.get(i), b = other.get(i);
				if (a.rank != b.rank || !a.player.equals(b.player) || !a.time.equals(b.time)) return false;
			}
			return true;
		}
	}

	public int currentLevel = 1;
	public boolean tracking = true;
	public boolean hudVisible = true;

	/** The run in progress (not saved: "transient" keeps it out of stats.json). */
	public transient RunStats currentRun = null;
	/** The last finished run (end loot collected). Null before your first finished dungeon. */
	public RunStats lastRun = null;
	/** Dungeon number -> stats for that dungeon. */
	public TreeMap<Integer, LevelStats> levels = new TreeMap<>();
	/** Every dungeon ever. */
	public Tally total = null;
	/** Dungeon number -> its "Fastest Times" leaderboard (read from the server's menu). */
	public TreeMap<Integer, Leaderboard> leaderboards = new TreeMap<>();
	/** How many dungeon runs were finished (since this counter was added). */
	public int dungeonsRun = 0;

	public LevelStats level(int level) {
		return levels.computeIfAbsent(level, l -> new LevelStats());
	}

	public LevelStats current() {
		return level(currentLevel);
	}

	/**
	 * Called when you enter a dungeon. Starts a new run, and throws away an unfinished one.
	 * If you re-enter the same dungeon while its run isn't finished (e.g. after dying or rejoining),
	 * the run continues instead of starting over.
	 */
	public RunStats startRun(int level, String name) {
		if (currentRun != null && !currentRun.completed && currentRun.level == level) {
			if (name != null) currentRun.name = name;
			return currentRun;
		}

		RunStats run = new RunStats();
		run.level = level;
		run.name = name != null ? name : (levels.containsKey(level) ? levels.get(level).name : null);
		run.startedAt = System.currentTimeMillis();
		currentRun = run;
		return run;
	}

	/** The run in progress. If loot shows up without an "entered dungeon" message, a run is started for the current level. */
	public RunStats run() {
		if (currentRun == null || currentRun.level != currentLevel) {
			startRun(currentLevel, null);
		}
		return currentRun;
	}

	/**
	 * End loot collected: adds the run to the saved stats (per dungeon, all-time total, chests per run)
	 * and makes it the "last dungeon". Does nothing if the run was already added.
	 */
	public void completeRun() {
		RunStats run = run();
		if (run.completed) return;
		run.completed = true;

		LevelStats levelStats = level(run.level);
		if (run.name != null) levelStats.name = run.name;
		levelStats.runs++;
		if (levelStats.chestsPerRun == null) levelStats.chestsPerRun = new ArrayList<>();
		levelStats.chestsPerRun.add(run.chestsLooted);
		levelStats.addAll(run);
		total.addAll(run);
		dungeonsRun++;
		lastRun = run;
	}

	/**
	 * Where a new chest or reward should be added. Before the end loot: only the run in progress.
	 * After it (e.g. the second end reward): the run and the saved stats.
	 */
	public List<Tally> talliesToUpdate() {
		RunStats run = run();
		return run.completed ? List.of(run, level(run.level), total) : List.of(run);
	}

	/**
	 * Throws away the run in progress (you lost all your lives). A run whose end loot was already
	 * collected is kept. Returns true if a run was deleted.
	 */
	public boolean discardRun() {
		if (currentRun == null || currentRun.completed) return false;
		currentRun = null;
		return true;
	}

	/** After the end loot, keeps the dungeon's chests-per-run history in step with the run (in case more chests come in). */
	public void updateRunChestHistory() {
		RunStats run = run();
		if (!run.completed) return;
		LevelStats levelStats = level(run.level);
		if (levelStats.chestsPerRun == null || levelStats.chestsPerRun.isEmpty()) return;
		levelStats.chestsPerRun.set(levelStats.chestsPerRun.size() - 1, run.chestsLooted);
	}

	/** "Prisma Dungeon #16" using the run's own name. */
	public String label(RunStats run) {
		if (run.name != null) return run.name + " #" + run.level;
		return label(run.level);
	}

	/** "Prisma Dungeon #16" if the name is known, otherwise "Dungeon #16". */
	public String label(int level) {
		LevelStats stats = levels.get(level);
		String name = stats != null && stats.name != null ? stats.name : "Dungeon";
		return name + " #" + level;
	}

	public void resetAll() {
		levels.clear();
		total = new Tally();
		lastRun = null;
		currentRun = null;
		dungeonsRun = 0;
	}

	public static StatsData load(Path file) {
		StatsData data = null;
		if (Files.exists(file)) {
			try (Reader reader = Files.newBufferedReader(file)) {
				data = GSON.fromJson(reader, StatsData.class);
			} catch (Exception e) {
				DungeonStatsClient.LOGGER.error("Could not read {}, starting fresh", file, e);
			}
		}
		if (data == null) data = new StatsData();
		if (data.levels == null) data.levels = new TreeMap<>();
		if (data.leaderboards == null) data.leaderboards = new TreeMap<>();
		// A last run saved by an older version was already added to the totals.
		if (data.lastRun != null) data.lastRun.completed = true;

		// Stats saved by an older version have no all-time total yet: build it from the per-dungeon stats.
		if (data.total == null) {
			data.total = new Tally();
			for (LevelStats level : data.levels.values()) {
				data.total.addAll(level);
			}
		}
		return data;
	}

	public void save(Path file) {
		try {
			Files.createDirectories(file.getParent());
			Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
			try (Writer writer = Files.newBufferedWriter(tmp)) {
				GSON.toJson(this, writer);
			}
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			DungeonStatsClient.LOGGER.error("Could not write {}", file, e);
		}
	}
}
