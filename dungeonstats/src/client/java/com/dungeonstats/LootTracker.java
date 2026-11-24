package com.dungeonstats;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.util.Util;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * Reads the dungeon server's chat messages:
 * <ul>
 *   <li>"You have entered Prisma Dungeon #16" -> switch to level 16 and start a new run</li>
 *   <li>"Added Dragon Egg, Star Remnant, Enchanted Golden Apple to the loot pool." -> +1 chest
 *       (+2 for a double chest), +1 of each item</li>
 *   <li>"clepaprika lost all of their lives!" -> if that's you, the unfinished run is deleted</li>
 *   <li>"Collected 3 x Dragon Eggs, 11 x Embers, ... from the loot pool." -> end reward claim, counted
 *       separately from normal chests</li>
 * </ul>
 * Anything after the item list, like "(~12 deggs)", is ignored. All patterns live in config.json, so they can be adjusted without rebuilding the mod.
 *
 * Double chests: the server sends one message per chest, so we remember whether the last chest you
 * right-clicked was a double chest. As a fallback we look at the block under your crosshair.
 */
public class LootTracker {
	/** How long after right-clicking a chest its loot message may arrive (collecting can take a while). */
	private static final long CLICK_VALID_MS = 30_000;

	private static final Pattern ITEM_SPLIT = Pattern.compile("\\s*,\\s*|\\s+and\\s+");
	private static final Pattern COUNT_BEFORE = Pattern.compile("^(\\d+)\\s*[xX×]?\\s+(.+)$"); // "3 Ember", "3x Ember"
	private static final Pattern COUNT_AFTER = Pattern.compile("^(.+?)\\s*[xX×]\\s*(\\d+)$"); // "Ember x3"

	private final CachedPattern enterPattern = new CachedPattern();
	private final CachedPattern lootPattern = new CachedPattern();
	private final CachedPattern bonusPattern = new CachedPattern();
	private final CachedPattern progressPattern = new CachedPattern();
	private final CachedPattern rewardPattern = new CachedPattern();
	private final CachedPattern livesLostPattern = new CachedPattern();
	private final CachedPattern completedPattern = new CachedPattern();
	private final CachedPattern playerCompletedPattern = new CachedPattern();

	/** Splits "clepaprika, Hyunn_W0o and Bob" into the separate names. */
	public static String[] splitNames(String names) {
		return names.strip().split("\\s*,\\s*|\\s+and\\s+|\\s*&\\s*");
	}

	// The completion both messages are about, so the time is recorded once and both comparisons
	// use the same "previous best" (your best from before this run).
	private int completionLevel = -1;
	private double completionSeconds = -1;
	private Double completionPreviousBest = null;

	/**
	 * A dungeon you were in was completed: record the time (once) and post the leaderboard comparison in chat.
	 * Called for both "Dungeon completed in X seconds!" and "<you> completed <dungeon> #N in X seconds!".
	 *
	 * @param teammates the other players, or null if unknown (first message) or solo
	 * @param knowsTeam true when the message lists the players (then teammates is reliable)
	 */
	private void onCompletion(int number, String dungeonName, double seconds, String teammates, boolean knowsTeam) {
		StatsData stats = DungeonStatsClient.stats();
		StatsData.LevelStats level = stats.level(number);
		if (level.name == null && !"Dungeon".equals(dungeonName)) level.name = dungeonName;

		boolean sameCompletion = number == completionLevel && Math.abs(seconds - completionSeconds) < 0.05;
		if (!sameCompletion) {
			// First message about this completion: remember the previous best, then record the new time.
			completionLevel = number;
			completionSeconds = seconds;
			completionPreviousBest = level.bestTime > 0 ? level.bestTime : null;
			level.recordTime(seconds, teammates);
		} else if (knowsTeam && Math.abs(level.bestTime - seconds) < 0.05) {
			// Second message: it lists the players, so fill in who was with you on this best time.
			level.bestTimeWith = teammates;
		}
		DungeonStatsClient.saveStats();

		Minecraft client = Minecraft.getInstance();
		String me = client.player != null ? client.player.getGameProfile().name() : "";
		DungeonStatsClient.chatNextTick(LeaderboardCompare.compare(me, number, dungeonName, seconds, completionPreviousBest));
	}

	/** "clepaprika, Hyunn_W0o" -> "Hyunn_W0o" (everyone except you), or null if you were alone. */
	private static String teammates(String names) {
		StringBuilder sb = new StringBuilder();
		for (String name : splitNames(names)) {
			if (name.isBlank() || isMe(name)) continue;
			if (sb.length() > 0) sb.append(", ");
			sb.append(name.strip());
		}
		return sb.length() == 0 ? null : sb.toString();
	}

	/** Is the player using the mod one of these names? */
	public static boolean listIncludesMe(String names) {
		for (String name : splitNames(names)) {
			if (isMe(name)) return true;
		}
		return false;
	}

	/** Is this name the player using the mod? Compares with the account name and the name shown in game. */
	public static boolean isMe(String name) {
		Minecraft client = Minecraft.getInstance();
		if (client.player == null || name == null) return false;
		String cleaned = name.strip();
		return cleaned.equalsIgnoreCase(client.player.getGameProfile().name())
				|| cleaned.equalsIgnoreCase(client.player.getName().getString());
	}

	private boolean lastClickWasDoubleChest = false;
	private long lastChestClickAt = 0;

	/** Called when you right-click any block. Never blocks the interaction. */
	public InteractionResult onUseBlock(Level level, BlockPos pos) {
		if (level.isClientSide()) {
			BlockState state = level.getBlockState(pos);
			if (state.getBlock() instanceof ChestBlock) {
				lastClickWasDoubleChest = isDoubleChest(state);
				lastChestClickAt = Util.getMillis();
			}
		}
		return InteractionResult.PASS;
	}

	private static boolean isDoubleChest(BlockState state) {
		return state.getBlock() instanceof ChestBlock && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE;
	}

	/** Was the chest that produced this loot message a double chest? */
	private boolean consumeDoubleChest() {
		boolean recentClick = lastChestClickAt != 0 && Util.getMillis() - lastChestClickAt <= CLICK_VALID_MS;
		boolean result;

		if (recentClick) {
			result = lastClickWasDoubleChest;
		} else {
			// Fallback: the chest under the crosshair.
			Minecraft client = Minecraft.getInstance();
			result = client.level != null
					&& client.hitResult instanceof BlockHitResult hit
					&& hit.getType() == HitResult.Type.BLOCK
					&& isDoubleChest(client.level.getBlockState(hit.getBlockPos()));
		}

		lastChestClickAt = 0; // one click = one chest message
		return result;
	}

	public void resetRun() {
		lastChestClickAt = 0;
		lastClickWasDoubleChest = false;
	}

	/**
	 * Looks inside a server message for hover text (the tooltip you see when hovering the loot progress bar),
	 * e.g. "Progress: 22/68 (32.4%)", and remembers how many chests the dungeon has.
	 */
	public void onServerComponent(Component message) {
		List<String> hoverTexts = new ArrayList<>();
		collectHoverTexts(message, hoverTexts, 0);
		if (hoverTexts.isEmpty()) return;

		ModConfig config = DungeonStatsClient.config();
		for (String hover : hoverTexts) {
			Matcher m = progressPattern.matcher(config.progressPattern, hover);
			if (m == null || !m.find()) continue;
			try {
				int total = Integer.parseInt(m.group(2));
				if (total <= 0) continue;
				StatsData stats = DungeonStatsClient.stats();
				StatsData.RunStats run = stats.run();
				run.chestsInDungeon = total; // everyone's chests; the HUD divides by the player count
				int perPlayer = Math.round((float) total / Math.max(1, DungeonStatsClient.sidebar().players()));
				StatsData.LevelStats level = stats.level(run.level);
				if (level.chestsInDungeon != perPlayer) {
					level.chestsInDungeon = perPlayer;
					DungeonStatsClient.saveStats();
				}
			} catch (RuntimeException ignored) {
			}
			return;
		}
	}

	private static void collectHoverTexts(Component component, List<String> out, int depth) {
		if (component == null || depth > 20) return;
		HoverEvent hover = component.getStyle().getHoverEvent();
		if (hover instanceof HoverEvent.ShowText showText) {
			out.add(showText.value().getString());
		}
		for (Component sibling : component.getSiblings()) {
			collectHoverTexts(sibling, out, depth + 1);
		}
	}

	public void onServerMessage(String rawText) {
		String text = rawText.strip();
		ModConfig config = DungeonStatsClient.config();
		StatsData stats = DungeonStatsClient.stats();

		// 1. Entering a dungeon (always detected, even while paused, so the level stays right).
		if (config.autoDetectLevel) {
			Matcher m = enterPattern.matcher(config.enterDungeonPattern, text);
			if (m != null && m.find()) {
				try {
					int level = Integer.parseInt(m.group(2));
					stats.currentLevel = level;
					DungeonStatsClient.sidebar().scheduleRead(); // count the players once, a few seconds from now
					StatsData.RunStats before = stats.currentRun;
					if (stats.startRun(level, m.group(1).strip()) != before) {
						DungeonStatsClient.kills().startRun(); // new run (not a re-entry): kills back to 0
						DungeonStatsClient.timer().start();
					}
					resetRun();
					DungeonStatsClient.saveStats(); // only saves the current level; the run itself isn't saved yet
					DungeonStatsClient.LOGGER.info("Entered {}", stats.label(stats.currentRun));
				} catch (RuntimeException e) {
					DungeonStatsClient.LOGGER.warn("Could not read dungeon number from '{}'", text);
				}
				return;
			}
		}

		// 2. Someone lost all their lives. If it's you, the run can't be finished: delete it.
		Matcher lost = livesLostPattern.matcher(config.livesLostPattern, text);
		if (lost != null && lost.find()) {
			if (isMe(lost.group(1)) && stats.discardRun()) {
				DungeonStatsClient.timer().stop();
				DungeonStatsClient.LOGGER.info("Lost all lives: run deleted");
				Minecraft client = Minecraft.getInstance();
				if (client.player != null) {
					client.player.sendOverlayMessage(Component.literal("Dungeon Stats: run deleted (no lives left)"));
				}
			}
			return;
		}

		// 3a. "clepaprika completed Prisma Dungeon #24 in 724.6 seconds!": compare your time with the leaderboard.
		Matcher playerDone = playerCompletedPattern.matcher(config.playerCompletedPattern, text);
		if (playerDone != null && playerDone.find()) {
			if (listIncludesMe(playerDone.group(1))) { // "clepaprika, Hyunn_W0o" -> is one of them you?
				try {
					int number = Integer.parseInt(playerDone.group(3));
					double seconds = Double.parseDouble(playerDone.group(4).replace(',', '.'));
					onCompletion(number, playerDone.group(2).strip(), seconds, teammates(playerDone.group(1)), true);
				} catch (RuntimeException e) {
					DungeonStatsClient.LOGGER.warn("Could not compare time from '{}'", text, e);
				}
			}
			return;
		}

		// 3b. "Dungeon completed in 724.6 seconds!": stop the timer, show the server's time, and compare it with
		//     the leaderboard of the dungeon you're in (this message has no dungeon number).
		Matcher completed = completedPattern.matcher(config.completedPattern, text);
		if (completed != null && completed.find()) {
			DungeonStatsClient.timer().complete(completed.group(1));
			try {
				double seconds = Double.parseDouble(completed.group(1).replace(',', '.'));
				StatsData.RunStats run = stats.run();
				String name = run.name != null ? run.name : stats.level(run.level).name;
				onCompletion(run.level, name != null ? name : "Dungeon", seconds, null, false);
			} catch (RuntimeException e) {
				DungeonStatsClient.LOGGER.warn("Could not compare time from '{}'", text, e);
			}
			return;
		}

		if (!stats.tracking) return;

		// 4a. A bonus chest ("Added bonus loot ... to the loot pool"): items count now, the chest itself shows as
		//     "+1" on the HUD and is added to the looted chests when the run is saved.
		Matcher bonus = bonusPattern.matcher(config.bonusLootPattern, text);
		if (bonus != null && bonus.find()) {
			StatsData.RunStats run = stats.run();
			if (!run.completed) run.bonusChests++;
			for (StatsData.Tally tally : stats.talliesToUpdate()) {
				if (run.completed) tally.chestsLooted++; // after the end loot: straight into the counter
				addItems(bonus.group(1), tally.items, tally.loot);
			}
			stats.updateRunChestHistory();
			if (run.completed) DungeonStatsClient.saveStats();
			return;
		}

		// 4b. A chest was collected.
		Matcher loot = lootPattern.matcher(config.lootPoolPattern, text);
		if (loot != null && loot.find()) {
			int chests = consumeDoubleChest() ? 2 : 1;
			for (StatsData.Tally tally : stats.talliesToUpdate()) {
				tally.chestsLooted += chests;
				addItems(loot.group(1), tally.items, tally.loot);
			}
			stats.updateRunChestHistory();
			if (stats.run().completed) DungeonStatsClient.saveStats(); // unfinished runs are not saved
			return;
		}

		// 5. An end-of-dungeon reward chest was claimed (separate section).
		Matcher reward = rewardPattern.matcher(config.rewardPattern, text);
		if (reward != null && reward.find()) {
			// First end reward of the run: the run is finished and gets added to the saved stats.
			stats.completeRun();
			for (StatsData.Tally tally : stats.talliesToUpdate()) {
				tally.rewardsClaimed++;
				addItems(reward.group(1), tally.rewardItems, tally.rewardLoot);
			}
			DungeonStatsClient.saveStats();
		}
	}

	/**
	 * Parses "Prisma Shard, 3 x Dragon Eggs, Star Remnant" and adds each item to {@code items},
	 * and to {@code categoryTotals} under its category (Dragon Eggs, Embers, ...) if it has one.
	 */
	private static void addItems(String list, Map<String, Integer> items, Map<String, Integer> categoryTotals) {
		ModConfig config = DungeonStatsClient.config();
		for (String part : ITEM_SPLIT.split(list.strip())) {
			if (part.isBlank()) continue;
			String item = part.strip();
			int count = 1;

			Matcher before = COUNT_BEFORE.matcher(item);
			Matcher after = COUNT_AFTER.matcher(item);
			if (before.matches()) {
				count = Integer.parseInt(before.group(1));
				item = before.group(2).strip();
			} else if (after.matches()) {
				item = after.group(1).strip();
				count = Integer.parseInt(after.group(2));
			}

			item = Plurals.singular(item); // "11 x Embers" and "Ember" are the same item
			items.merge(item, count, Integer::sum);
			for (ModConfig.Category category : config.categories) {
				if (category.matches(item)) {
					categoryTotals.merge(category.name, count, Integer::sum);
					break;
				}
			}
		}
	}

	/** Compiles a regex from the config, and recompiles only if the config text changes (e.g. after /dstats reload). */
	private static final class CachedPattern {
		private String source;
		private Pattern pattern;
		private boolean broken;

		Matcher matcher(String regex, String text) {
			if (regex == null || regex.isEmpty()) return null;
			if (!regex.equals(source)) {
				source = regex;
				try {
					pattern = Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
					broken = false;
				} catch (PatternSyntaxException e) {
					DungeonStatsClient.LOGGER.error("Invalid pattern in config.json: {}", regex, e);
					broken = true;
				}
			}
			return broken ? null : pattern.matcher(text);
		}
	}
}
