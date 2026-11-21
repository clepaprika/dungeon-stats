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

	public static ModConfig config() {
		return config;
	}

	public static StatsData stats() {
		return stats;
	}

	public static void saveStats() {
		stats.save(STATS_FILE);
	}

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
			if (!overlay) TRACKER.onServerMessage(message.getString());
		});
		ClientReceiveMessageEvents.CHAT.register((message, signed, sender, type, time) -> {
			if (sender == null) TRACKER.onServerMessage(message.getString());
		});
		UseBlockCallback.EVENT.register((player, level, hand, hitResult) ->
				TRACKER.onUseBlock(level, hitResult.getBlockPos()));
		ClientPlayConnectionEvents.DISCONNECT.register((listener, client) -> TRACKER.resetRun());

		// --- HUD ---
		HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, id("stats_hud"), StatsHud::extract);

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
									stats.resetAll();
									TRACKER.resetRun();
									saveStats();
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

	/** /dstats: the last dungeon run and the all-time total. */
	private static int showSummary(FabricClientCommandSource source) {
		StatsData.RunStats run = stats.lastRun;
		if (run == null) {
			source.sendFeedback(Component.literal("No dungeon recorded yet. Go loot some chests!"));
		} else {
			source.sendFeedback(Component.literal("=== Last dungeon: " + stats.label(run.level) + " ===").withStyle(ChatFormatting.GOLD));
			sendTally(source, run);
		}

		String runs = stats.dungeonsRun > 0 ? " (" + stats.dungeonsRun + " runs)" : "";
		source.sendFeedback(Component.literal("=== All dungeons" + runs + " ===").withStyle(ChatFormatting.GOLD));
		sendTally(source, stats.total);
		source.sendFeedback(Component.literal("One dungeon: /dstats <number>  |  Per dungeon: /dstats levels  |  Items: /dstats items [all|<number>]")
				.withStyle(ChatFormatting.GRAY));
		return Command.SINGLE_SUCCESS;
	}

	/** /dstats 26: chests per run for one dungeon, plus its totals. */
	private static int showDungeon(FabricClientCommandSource source, int number) {
		StatsData.LevelStats level = stats.levels.get(number);
		if (level == null) {
			source.sendFeedback(Component.literal("No data yet for Dungeon #" + number + "."));
			return Command.SINGLE_SUCCESS;
		}

		source.sendFeedback(Component.literal("=== " + stats.label(number) + " ===").withStyle(ChatFormatting.GOLD));

		List<Integer> history = level.chestsPerRun;
		if (history == null || history.isEmpty()) {
			source.sendFeedback(Component.literal("  No runs recorded yet (runs are recorded from this version on)."));
		} else {
			int last = history.get(history.size() - 1);
			source.sendFeedback(Component.literal("  Chests in this dungeon: " + level.mostChestsInRun()
					+ " (most looted in one run)").withStyle(ChatFormatting.YELLOW));
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

		source.sendFeedback(Component.literal("All runs combined:").withStyle(ChatFormatting.GOLD));
		sendTally(source, level);
		return Command.SINGLE_SUCCESS;
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

	/** "5 looted, 1 Dragon Eggs, 3 Embers, 2 Star Remnants" */
	private static String summary(int count, String verb, Map<String, Integer> loot) {
		StringBuilder sb = new StringBuilder().append(count).append(' ').append(verb);
		for (ModConfig.Category category : config.categories) {
			sb.append(", ").append(loot.getOrDefault(category.name, 0)).append(' ').append(category.name);
		}
		return sb.toString();
	}

	private static int showLastRunItems(FabricClientCommandSource source) {
		StatsData.RunStats run = stats.lastRun;
		return showItems(source, run == null ? "Last dungeon" : "Last dungeon: " + stats.label(run.level), run);
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
		items.entrySet().stream()
				.sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
				.forEach(e -> source.sendFeedback(Component.literal("  " + e.getValue() + "x " + e.getKey())));
	}
}
