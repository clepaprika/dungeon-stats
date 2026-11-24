package com.dungeonstats;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import org.lwjgl.sdl.SDLScancode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.loader.api.FabricLoader;

public class DungeonStatsClient implements ClientModInitializer {
	public static final String MOD_ID = "dungeonstats";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static final Path DIR = FabricLoader.getInstance().getConfigDir().resolve(MOD_ID);
	private static final Path CONFIG_FILE = DIR.resolve("config.json");
	private static final Path STATS_FILE = DIR.resolve("stats.json");

	private static ModConfig config;
	private static StatsData stats;
	private static final LootTracker TRACKER = new LootTracker();
	private static final KillCounter KILLS = new KillCounter();

	private static final RunTimer TIMER = new RunTimer();
	private static final LeaderboardReader LEADERBOARDS = new LeaderboardReader();
	private static final SidebarReader SIDEBAR = new SidebarReader();

	public static SidebarReader sidebar() {
		return SIDEBAR;
	}
	/** Chat lines to show on the next tick, so they appear after the server message that triggered them. */
	private static final java.util.Queue<Component> PENDING_CHAT = new java.util.ArrayDeque<>();

	public static void chatNextTick(Iterable<Component> lines) {
		for (Component line : lines) PENDING_CHAT.add(line);
	}

	public static RunTimer timer() {
		return TIMER;
	}

	public static KillCounter kills() {
		return KILLS;
	}

	public static ModConfig config() {
		return config;
	}

	public static StatsData stats() {
		return stats;
	}

	public static void saveStats() {
		stats.save(STATS_FILE);
	}

	public static void saveConfig() {
		config.save(CONFIG_FILE);
	}

	/** Deletes every saved stat (runs, totals, chest counts, leaderboards). Settings are kept. */
	public static void resetAllData() {
		stats.resetAll();
		stats.leaderboards.clear();
		TRACKER.resetRun();
		saveStats();
	}

	/** Set by /dstats settings; the screen opens on the next tick, after the chat screen has closed. */
	private static boolean openSettingsNextTick = false;

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}

	@Override
	public void onInitializeClient() {
		config = ModConfig.load(CONFIG_FILE);
		stats = StatsData.load(STATS_FILE);

		// --- Read the server's dungeon messages ---
		// Server plugins send these as system messages; CHAT (with no player sender) is checked too in case
		// the server routes them that way. All patterns are anchored to the start of the message, so a player
		// typing the same words in chat (prefixed by their name) won't be counted.
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
			if (!overlay) {
				TRACKER.onServerMessage(message.getString());
				TRACKER.onServerComponent(message); // hover text, e.g. the loot progress bar
			}
		});
		ClientReceiveMessageEvents.CHAT.register((message, signed, sender, type, time) -> {
			if (sender == null) TRACKER.onServerMessage(message.getString());
		});
		UseBlockCallback.EVENT.register((player, level, hand, hitResult) ->
				TRACKER.onUseBlock(level, hitResult.getBlockPos()));
		ClientPlayConnectionEvents.DISCONNECT.register((listener, client) -> TRACKER.resetRun());

		// --- HUD ---
		HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, id("stats_hud"), StatsHud::extract);

		// The server's scoreboard sidebar (map, Lives, Players): drawn as usual unless hidden in /dstats settings.
		HudElementRegistry.replaceElement(VanillaHudElements.SCOREBOARD, original -> (graphics, deltaTracker) -> {
			if (!config.hideSidebar) original.extractRenderState(graphics, deltaTracker);
		});

		// --- Key mappings (change them in Options > Controls > Key Binds > Dungeon Stats) ---
		KeyMapping.Category category = KeyMapping.Category.register(id("main"));
		KeyMapping toggleHud = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.dungeonstats.toggle_hud", InputConstants.Type.KEYBOARD, SDLScancode.SDL_SCANCODE_J, category));
		KeyMapping nextLevel = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.dungeonstats.next_level", InputConstants.Type.KEYBOARD, SDLScancode.SDL_SCANCODE_PAGEUP, category));
		KeyMapping prevLevel = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.dungeonstats.prev_level", InputConstants.Type.KEYBOARD, SDLScancode.SDL_SCANCODE_PAGEDOWN, category));
		KeyMapping toggleTracking = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.dungeonstats.toggle_tracking", InputConstants.Type.KEYBOARD, SDLScancode.SDL_SCANCODE_K, category));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (client.player == null) return;
			KILLS.onClientTick(client);
			LEADERBOARDS.onClientTick(client);
			SIDEBAR.onClientTick(client);
			if (openSettingsNextTick) {
				openSettingsNextTick = false;
				client.gui.setScreen(new SettingsScreen(null));
			}
			while (!PENDING_CHAT.isEmpty()) client.player.sendSystemMessage(PENDING_CHAT.poll());

			while (toggleHud.consumeClick()) {
				stats.hudVisible = !stats.hudVisible;
				saveStats();
			}
			while (nextLevel.consumeClick()) {
				setLevel(stats.currentLevel + 1);
				actionBar(client, stats.label(stats.currentLevel));
			}
			while (prevLevel.consumeClick()) {
				setLevel(Math.max(1, stats.currentLevel - 1));
				actionBar(client, stats.label(stats.currentLevel));
			}
			while (toggleTracking.consumeClick()) {
				stats.tracking = !stats.tracking;
				saveStats();
				actionBar(client, stats.tracking ? "Dungeon tracking resumed" : "Dungeon tracking paused");
			}
		});

		// --- /dstats commands ---
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) -> dispatcher.register(
				ClientCommands.literal("dstats")
						.executes(ctx -> showSummary(ctx.getSource()))
						.then(ClientCommands.argument("dungeon", IntegerArgumentType.integer(1)).executes(ctx ->
								showDungeon(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "dungeon"))))
						.then(ClientCommands.literal("levels").executes(ctx -> showLevels(ctx.getSource())))
						.then(ClientCommands.literal("help").executes(ctx -> showHelp(ctx.getSource())))
						.then(ClientCommands.literal("debug").then(ClientCommands.literal("sidebar").executes(ctx -> {
							// Shows what the mod reads from the "Players:" line, to help fix the player count.
							List<String> info = new java.util.ArrayList<>();
							try {
								SidebarReader.countPlayers(SidebarReader.sidebarLines(Minecraft.getInstance()), info);
							} catch (RuntimeException e) {
								info.add("Could not read the sidebar: " + e);
							}
							ctx.getSource().sendFeedback(Component.literal("=== Sidebar debug ===").withStyle(ChatFormatting.GOLD));
							for (String line : info) ctx.getSource().sendFeedback(Component.literal(line));
							ctx.getSource().sendFeedback(Component.literal("Players used for chest counts (read when the dungeon started): " + SIDEBAR.players()));
							LOGGER.info("Sidebar debug: {}", info);
							return Command.SINGLE_SUCCESS;
						})))
						.then(ClientCommands.literal("sidebar").executes(ctx -> {
							config.hideSidebar = !config.hideSidebar;
							saveConfig();
							ctx.getSource().sendFeedback(Component.literal("Server sidebar " + (config.hideSidebar ? "hidden." : "shown.")));
							return Command.SINGLE_SUCCESS;
						}))
						.then(ClientCommands.literal("settings").executes(ctx -> {
							openSettingsNextTick = true;
							return Command.SINGLE_SUCCESS;
						}))
						.then(ClientCommands.literal("items")
								.executes(ctx -> showLastRunItems(ctx.getSource()))
								.then(ClientCommands.literal("all").executes(ctx ->
										showItems(ctx.getSource(), "All dungeons", stats.total)))
								.then(ClientCommands.argument("level", IntegerArgumentType.integer(1)).executes(ctx -> {
									int level = IntegerArgumentType.getInteger(ctx, "level");
									return showItems(ctx.getSource(), stats.label(level) + " (all runs)", stats.levels.get(level));
								})))
						.then(ClientCommands.literal("level")
								.then(ClientCommands.argument("level", IntegerArgumentType.integer(1)).executes(ctx -> {
									setLevel(IntegerArgumentType.getInteger(ctx, "level"));
									ctx.getSource().sendFeedback(Component.literal("Now tracking " + stats.label(stats.currentLevel)));
									return Command.SINGLE_SUCCESS;
								})))
						.then(ClientCommands.literal("pause").executes(ctx -> {
							stats.tracking = false;
							saveStats();
							ctx.getSource().sendFeedback(Component.literal("Tracking paused."));
							return Command.SINGLE_SUCCESS;
						}))
						.then(ClientCommands.literal("resume").executes(ctx -> {
							stats.tracking = true;
							saveStats();
							ctx.getSource().sendFeedback(Component.literal("Tracking resumed."));
							return Command.SINGLE_SUCCESS;
						}))
						.then(hudCommand())
						.then(ClientCommands.literal("reset")
								.executes(ctx -> {
									resetAllData();
									ctx.getSource().sendFeedback(Component.literal("All dungeon stats cleared."));
									return Command.SINGLE_SUCCESS;
								})
								.then(ClientCommands.argument("level", IntegerArgumentType.integer(1)).executes(ctx -> {
									int level = IntegerArgumentType.getInteger(ctx, "level");
									stats.levels.remove(level);
									if (level == stats.currentLevel) TRACKER.resetRun();
									saveStats();
									ctx.getSource().sendFeedback(Component.literal(
											"Stats cleared for level " + level + ". The all-time total is unchanged."));
									return Command.SINGLE_SUCCESS;
								})))
						.then(ClientCommands.literal("reload").executes(ctx -> {
							config = ModConfig.load(CONFIG_FILE);
							ctx.getSource().sendFeedback(Component.literal("Dungeon Stats config reloaded."));
							return Command.SINGLE_SUCCESS;
						}))
		));

		LOGGER.info("Dungeon Stats loaded ({} loot categories)", config.categories.size());
	}

	/**
	 * /dstats hud                       -> show / hide
	 * /dstats hud scale <0.25-4>        -> size (1 = normal)
	 * /dstats hud position <x> <y>      -> distance in pixels from the corner
	 * /dstats hud corner <top_left|...> -> which screen corner the HUD sticks to
	 * /dstats hud reset                 -> back to defaults
	 */
	private static LiteralArgumentBuilder<FabricClientCommandSource> hudCommand() {
		LiteralArgumentBuilder<FabricClientCommandSource> corner = ClientCommands.literal("corner");
		for (String name : ModConfig.CORNERS) {
			corner.then(ClientCommands.literal(name).executes(ctx -> {
				config.hudCorner = name;
				config.save(CONFIG_FILE);
				ctx.getSource().sendFeedback(Component.literal("HUD now sticks to the " + name.replace('_', ' ') + " corner."));
				return Command.SINGLE_SUCCESS;
			}));
		}

		return ClientCommands.literal("hud")
				.executes(ctx -> {
					stats.hudVisible = !stats.hudVisible;
					saveStats();
					return Command.SINGLE_SUCCESS;
				})
				.then(ClientCommands.literal("scale")
						.then(ClientCommands.argument("scale", FloatArgumentType.floatArg(ModConfig.MIN_SCALE, ModConfig.MAX_SCALE)).executes(ctx -> {
							config.hudScale = FloatArgumentType.getFloat(ctx, "scale");
							config.save(CONFIG_FILE);
							ctx.getSource().sendFeedback(Component.literal("HUD scale set to " + config.hudScale + "."));
							return Command.SINGLE_SUCCESS;
						})))
				.then(ClientCommands.literal("position")
						.then(ClientCommands.argument("x", IntegerArgumentType.integer(0))
								.then(ClientCommands.argument("y", IntegerArgumentType.integer(0)).executes(ctx -> {
									config.hudX = IntegerArgumentType.getInteger(ctx, "x");
									config.hudY = IntegerArgumentType.getInteger(ctx, "y");
									config.save(CONFIG_FILE);
									ctx.getSource().sendFeedback(Component.literal(
											"HUD moved to " + config.hudX + ", " + config.hudY + " from the " + config.hudCorner.replace('_', ' ') + " corner."));
									return Command.SINGLE_SUCCESS;
								}))))
				.then(corner)
				.then(ClientCommands.literal("reset").executes(ctx -> {
					config.hudCorner = "top_left";
					config.hudX = 4;
					config.hudY = 4;
					config.hudScale = 1.0f;
					config.save(CONFIG_FILE);
					ctx.getSource().sendFeedback(Component.literal("HUD position and size reset."));
					return Command.SINGLE_SUCCESS;
				}));
	}

	private static void setLevel(int level) {
		if (level != stats.currentLevel) {
			stats.currentLevel = level;
			TRACKER.resetRun();
			saveStats();
		}
	}

	private static void actionBar(Minecraft client, String message) {
		if (client.player != null) client.player.sendOverlayMessage(Component.literal(message));
	}

	/** /dstats help: every command with a one-line description. */
	private static int showHelp(FabricClientCommandSource source) {
		String[][] commands = {
				{"/dstats", "Run in progress, last dungeon and all-time totals"},
				{"/dstats <number>", "One dungeon: chests per run, totals, leaderboard"},
				{"/dstats levels", "Every dungeon, summed over all your runs"},
				{"/dstats items", "Every item from your last dungeon"},
				{"/dstats items all", "Every item you've ever collected"},
				{"/dstats items <number>", "Every item from one dungeon"},
				{"/dstats settings", "Open the settings menu"},
				{"/dstats level <number>", "Set the current dungeon by hand"},
				{"/dstats pause", "Stop counting chests and loot"},
				{"/dstats resume", "Start counting again"},
				{"/dstats hud", "Show / hide the HUD"},
				{"/dstats sidebar", "Show / hide the server's sidebar (map, lives, players)"},
				{"/dstats debug sidebar", "Show how the player count is read from the sidebar"},
				{"/dstats hud scale <0.25-4>", "HUD size (1 = normal)"},
				{"/dstats hud position <x> <y>", "HUD distance from its corner"},
				{"/dstats hud corner <corner>", "Stick the HUD to a corner"},
				{"/dstats hud reset", "HUD back to default size and place"},
				{"/dstats reset", "Delete all data (asks no confirmation!)"},
				{"/dstats reset <number>", "Delete one dungeon's data"},
				{"/dstats reload", "Reload config.json after editing it"},
				{"/dstats help", "This list"},
		};

		source.sendFeedback(Component.literal("=== Dungeon Stats commands ===").withStyle(ChatFormatting.GOLD));
		for (String[] c : commands) {
			source.sendFeedback(Component.literal(c[0]).withStyle(ChatFormatting.YELLOW)
					.append(Component.literal(" - " + c[1]).withStyle(ChatFormatting.WHITE)));
		}
		source.sendFeedback(Component.literal("Keys: J show/hide HUD, K pause, Page Up/Down change dungeon")
				.withStyle(ChatFormatting.GRAY));
		return Command.SINGLE_SUCCESS;
	}

	/** /dstats: the run in progress (if any), the last finished dungeon and the all-time total. */
	private static int showSummary(FabricClientCommandSource source) {
		StatsData.RunStats current = stats.currentRun;
		if (current != null && !current.completed) {
			source.sendFeedback(Component.literal("=== In progress: " + stats.label(current) + " (saved when you collect the end loot) ===")
					.withStyle(ChatFormatting.GREEN));
			sendTally(source, current);
		}

		StatsData.RunStats run = stats.lastRun;
		if (run == null) {
			source.sendFeedback(Component.literal("No finished dungeon yet. Stats are saved when you collect the end loot."));
		} else {
			source.sendFeedback(Component.literal("=== Last dungeon: " + stats.label(run) + " ===").withStyle(ChatFormatting.GOLD));
			sendTally(source, run);
		}

		String runs = stats.dungeonsRun > 0 ? " (" + stats.dungeonsRun + " runs)" : "";
		source.sendFeedback(Component.literal("=== All dungeons" + runs + " ===").withStyle(ChatFormatting.GOLD));
		sendTally(source, stats.total);
		source.sendFeedback(Component.literal("One dungeon: /dstats <number>  |  Per dungeon: /dstats levels  |  Items: /dstats items [all|<number>]")
				.withStyle(ChatFormatting.GRAY));
		return Command.SINGLE_SUCCESS;
	}

	/** /dstats 26: chests per run for one dungeon, its totals, and its leaderboard. */
	private static int showDungeon(FabricClientCommandSource source, int number) {
		StatsData.LevelStats level = stats.levels.get(number);
		StatsData.Leaderboard board = stats.leaderboards.get(number);
		if (level == null && board == null) {
			source.sendFeedback(Component.literal("No data yet for Dungeon #" + number
					+ ". Finish a run, or open its Fastest Times menu to load the leaderboard."));
			return Command.SINGLE_SUCCESS;
		}

		String title = level != null && level.name != null ? stats.label(number)
				: board != null && board.dungeonName != null ? board.dungeonName + " #" + number
				: stats.label(number);
		source.sendFeedback(Component.literal("=== " + title + " ===").withStyle(ChatFormatting.GOLD));

		// Your fastest and last completion times.
		if (level != null && level.bestTime > 0) {
			String with = level.bestTimeWith != null ? ", with " + level.bestTimeWith : ", solo";
			source.sendFeedback(Component.literal("  Your fastest time: ").withStyle(ChatFormatting.AQUA)
					.append(Component.literal(LeaderboardCompare.formatTime(level.bestTime)).withStyle(ChatFormatting.GREEN))
					.append(Component.literal(" (" + ago(level.bestTimeAt) + with + ")").withStyle(ChatFormatting.GRAY)));
			if (level.lastTime > 0 && level.lastTime != level.bestTime) {
				source.sendFeedback(Component.literal("  Last time: " + LeaderboardCompare.formatTime(level.lastTime))
						.withStyle(ChatFormatting.GRAY));
			}
		} else {
			source.sendFeedback(Component.literal("  Your fastest time: none recorded yet").withStyle(ChatFormatting.GRAY));
		}

		if (level == null) {
			source.sendFeedback(Component.literal("  You haven't finished a run of this dungeon yet."));
		} else {
			List<Integer> history = level.chestsPerRun;
			if (history == null || history.isEmpty()) {
				source.sendFeedback(Component.literal("  No runs recorded yet (runs are recorded from this version on)."));
			} else {
				int last = history.get(history.size() - 1);
				if (level.chestsInDungeon > 0) {
					source.sendFeedback(Component.literal("  Chests in this dungeon: " + level.chestsInDungeon
							+ "  |  Most looted in one run: " + level.mostChestsInRun()).withStyle(ChatFormatting.YELLOW));
				} else {
					source.sendFeedback(Component.literal("  Chests in this dungeon: " + level.mostChestsInRun()
							+ " (most looted in one run)").withStyle(ChatFormatting.YELLOW));
				}
				source.sendFeedback(Component.literal("  Last run: " + last + " chests  |  Average: "
						+ String.format(Locale.ROOT, "%.1f", level.averageChestsPerRun())
						+ "  |  Runs: " + history.size()));

				// The last 10 runs, newest first.
				StringBuilder recent = new StringBuilder("  Recent runs: ");
				for (int i = history.size() - 1, shown = 0; i >= 0 && shown < 10; i--, shown++) {
					if (shown > 0) recent.append(", ");
					recent.append(history.get(i));
				}
				source.sendFeedback(Component.literal(recent.toString()).withStyle(ChatFormatting.GRAY));
			}

			if (level.runs > 0) {
				source.sendFeedback(Component.literal("Average per run (" + level.runs + " runs):").withStyle(ChatFormatting.GOLD));
				sendAverage(source, level, level.runs);
			} else {
				// Data from before runs were counted: averages aren't possible, show the totals.
				source.sendFeedback(Component.literal("All runs combined:").withStyle(ChatFormatting.GOLD));
				sendTally(source, level);
			}
		}

		// Leaderboard, as last read from the server's "Fastest Times" menu.
		source.sendFeedback(Component.literal("Leaderboard:").withStyle(ChatFormatting.LIGHT_PURPLE));
		if (board == null || board.entries == null || board.entries.isEmpty()) {
			source.sendFeedback(Component.literal("  Not loaded yet: open the Fastest Times menu for this dungeon."));
		} else {
			String me = Minecraft.getInstance().player != null ? Minecraft.getInstance().player.getGameProfile().name() : "";
			for (StatsData.LeaderboardEntry e : board.entries) {
				Component line = Component.literal("  #" + e.rank + " " + e.player + ": " + e.time);
				// Your own name stands out in green.
				source.sendFeedback(LeaderboardCompare.hasName(e.player, me) ? line.copy().withStyle(ChatFormatting.GREEN) : line);
			}
			String info = "  Updated " + ago(board.updatedAt) + (board.length != null ? "  |  Dungeon length: " + board.length : "");
			source.sendFeedback(Component.literal(info).withStyle(ChatFormatting.GRAY));
		}
		return Command.SINGLE_SUCCESS;
	}

	/** "just now", "5 min ago", "3 h ago", "2 days ago". */
	public static String ago(long time) {
		long minutes = (System.currentTimeMillis() - time) / 60_000;
		if (minutes < 1) return "just now";
		if (minutes < 60) return minutes + " min ago";
		if (minutes < 60 * 24) return (minutes / 60) + " h ago";
		return (minutes / (60 * 24)) + " days ago";
	}

	/** /dstats levels: every dungeon number, summed over all its runs. */
	private static int showLevels(FabricClientCommandSource source) {
		if (stats.levels.isEmpty()) {
			source.sendFeedback(Component.literal("No dungeon recorded yet. Go loot some chests!"));
			return Command.SINGLE_SUCCESS;
		}
		source.sendFeedback(Component.literal("=== Per dungeon ===").withStyle(ChatFormatting.GOLD));
		for (Map.Entry<Integer, StatsData.LevelStats> entry : stats.levels.entrySet()) {
			StatsData.LevelStats level = entry.getValue();
			source.sendFeedback(Component.literal(stats.label(entry.getKey()) + (level.runs > 0 ? " (" + level.runs + " runs)" : ""))
					.withStyle(ChatFormatting.YELLOW));
			sendTally(source, level);
		}
		return Command.SINGLE_SUCCESS;
	}

	/** Two lines: normal chests, then end rewards. */
	private static void sendTally(FabricClientCommandSource source, StatsData.Tally tally) {
		source.sendFeedback(Component.literal("  Chests: " + summary(tally.chestsLooted, "looted", tally.loot)));
		source.sendFeedback(Component.literal("  End rewards: " + summary(tally.rewardsClaimed, "claimed", tally.rewardLoot))
				.withStyle(ChatFormatting.AQUA));
	}

	/** Two lines like sendTally, but divided by the number of runs: "Chests: 16.4 looted, Dragon Eggs: 5.3, ..." */
	private static void sendAverage(FabricClientCommandSource source, StatsData.Tally tally, int runs) {
		source.sendFeedback(Component.literal("  Chests: " + averageSummary(tally.chestsLooted, "looted", tally.loot, runs)));
		source.sendFeedback(Component.literal("  End rewards: " + averageSummary(tally.rewardsClaimed, "claimed", tally.rewardLoot, runs))
				.withStyle(ChatFormatting.AQUA));
	}

	private static String averageSummary(int count, String verb, Map<String, Integer> loot, int runs) {
		StringBuilder sb = new StringBuilder(oneDecimal((double) count / runs)).append(' ').append(verb);
		// Embers fully converted into dragon eggs (4 embers = 1 egg), so there's no separate Embers line.
		config.averageLoot(loot, runs).forEach((name, amount) -> sb.append(", ").append(name).append(": ").append(oneDecimal(amount)));
		return sb.toString();
	}

	/** 5.25 -> "5.3", 5.0 -> "5" */
	private static String oneDecimal(double value) {
		String s = String.format(Locale.ROOT, "%.1f", value);
		return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
	}

	/** "5 looted, Dragon Eggs: 13, Embers: 2, Star Remnants: 5" */
	private static String summary(int count, String verb, Map<String, Integer> loot) {
		StringBuilder sb = new StringBuilder().append(count).append(' ').append(verb);
		// Embers folded into Dragon Eggs: "Dragon Eggs: 13, Embers: 2"
		config.displayLoot(loot).forEach((name, amount) -> sb.append(", ").append(name).append(": ").append(amount));
		return sb.toString();
	}

	private static int showLastRunItems(FabricClientCommandSource source) {
		StatsData.RunStats run = stats.lastRun;
		return showItems(source, run == null ? "Last dungeon" : "Last dungeon: " + stats.label(run), run);
	}

	private static int showItems(FabricClientCommandSource source, String title, StatsData.Tally tally) {
		if (tally == null || (tally.items.isEmpty() && tally.rewardItems.isEmpty())) {
			source.sendFeedback(Component.literal("No loot recorded yet for " + title + "."));
			return Command.SINGLE_SUCCESS;
		}

		source.sendFeedback(Component.literal("=== Items: " + title + " ===").withStyle(ChatFormatting.GOLD));
		source.sendFeedback(Component.literal("From chests:").withStyle(ChatFormatting.YELLOW));
		listItems(source, tally.items);
		source.sendFeedback(Component.literal("From end rewards:").withStyle(ChatFormatting.AQUA));
		listItems(source, tally.rewardItems);
		return Command.SINGLE_SUCCESS;
	}

	private static void listItems(FabricClientCommandSource source, Map<String, Integer> items) {
		if (items.isEmpty()) {
			source.sendFeedback(Component.literal("  (nothing yet)"));
			return;
		}
		combineCraftable(items).entrySet().stream()
				.sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
				.forEach(e -> source.sendFeedback(Component.literal("  " + e.getValue() + "x " + e.getKey())));
	}

	/**
	 * For display only: turns craftable items into what they craft, using the categories' recipes.
	 * {Dragon Egg=3, Ember=13} -> {Dragon Egg=6, Ember=1}. The saved data isn't changed.
	 */
	static Map<String, Integer> combineCraftable(Map<String, Integer> items) {
		Map<String, Integer> result = new java.util.LinkedHashMap<>(items);
		for (ModConfig.Category category : config.categories) {
			if (category.craftInto == null || category.craftInto.isBlank() || category.craftCost < 2) continue;

			// Take every item of this category out (e.g. all "Ember" entries) and count them up.
			int units = 0;
			for (String key : List.copyOf(result.keySet())) {
				if (category.matches(key)) units += result.remove(key);
			}
			if (units == 0) continue;

			int crafted = units / category.craftCost;
			int left = units % category.craftCost;

			if (crafted > 0) {
				// Add to the existing entry for the crafted item ("Dragon Egg"), whatever its capitalisation.
				String target = null;
				for (String key : result.keySet()) {
					if (key.equalsIgnoreCase(category.craftInto)) target = key;
				}
				if (target == null) target = titleCase(category.craftInto);
				result.merge(target, crafted, Integer::sum);
			}
			if (left > 0) {
				String unit = category.unitName != null && !category.unitName.isBlank() ? category.unitName : category.name;
				result.merge(titleCase(unit), left, Integer::sum);
			}
		}
		return result;
	}

	/** "dragon egg" -> "Dragon Egg" */
	private static String titleCase(String s) {
		StringBuilder sb = new StringBuilder();
		for (String word : s.strip().split("\\s+")) {
			if (word.isEmpty()) continue;
			if (sb.length() > 0) sb.append(' ');
			sb.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
		}
		return sb.toString();
	}
}
