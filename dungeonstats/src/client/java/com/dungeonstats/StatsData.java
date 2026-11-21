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
import java.util.TreeMap;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/**
 * Everything the mod remembers between game sessions, stored in config/dungeonstats/stats.json.
 *
 * The same counters are kept three times:
 * <ul>
 *   <li>{@link #lastRun}: the dungeon run you're in now, or the last one you did</li>
 *   <li>{@link #levels}: per dungeon number, summed over all runs of it</li>
 *   <li>{@link #total}: every dungeon ever, summed</li>
 * </ul>
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
	}

	public int currentLevel = 1;
	public boolean tracking = true;
	public boolean hudVisible = true;

	/** The run in progress, or the last finished one. Null before your first dungeon. */
	public RunStats lastRun = null;
	/** Dungeon number -> stats for that dungeon. */
	public TreeMap<Integer, LevelStats> levels = new TreeMap<>();
	/** Every dungeon ever. */
	public Tally total = null;
	/** How many dungeon runs were started (since this counter was added). */
	public int dungeonsRun = 0;

	public LevelStats level(int level) {
		return levels.computeIfAbsent(level, l -> new LevelStats());
	}

	public LevelStats current() {
		return level(currentLevel);
	}

	/** Starts a new run: the previous run stops being "the last dungeon". */
	public RunStats startRun(int level) {
		LevelStats levelStats = level(level);
		levelStats.runs++;
		if (levelStats.chestsPerRun == null) levelStats.chestsPerRun = new ArrayList<>();
		levelStats.chestsPerRun.add(0);
		dungeonsRun++;

		RunStats run = new RunStats();
		run.level = level;
		run.name = levelStats.name;
		run.startedAt = System.currentTimeMillis();
		lastRun = run;
		return run;
	}

	/** The current run. If loot shows up without an "entered dungeon" message, a run is started for the current level. */
	public RunStats run() {
		if (lastRun == null || lastRun.level != currentLevel) {
			startRun(currentLevel);
		}
		return lastRun;
	}

	/** Copies the current run's chest count into its dungeon's per-run history. */
	public void updateRunChestHistory() {
		RunStats run = run();
		LevelStats levelStats = level(run.level);
		if (levelStats.chestsPerRun == null) levelStats.chestsPerRun = new ArrayList<>();
		if (levelStats.chestsPerRun.isEmpty()) levelStats.chestsPerRun.add(0);
		levelStats.chestsPerRun.set(levelStats.chestsPerRun.size() - 1, run.chestsLooted);
	}

	/** Every set of counters a new chest or reward should be added to. */
	public List<Tally> talliesToUpdate() {
		return List.of(run(), current(), total);
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
