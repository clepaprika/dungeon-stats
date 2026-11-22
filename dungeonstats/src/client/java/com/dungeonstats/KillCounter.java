package com.dungeonstats;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.stats.Stats;

/**
 * Mobs killed during the current dungeon run. Shown on the HUD only, never saved.
 *
 * Uses Minecraft's own "Mobs Killed" statistic (the one in the Statistics screen), so only your kills count,
 * melee and ranged, and not your teammates'. The client only knows that statistic when it asks the server
 * for it, so we ask every few seconds (the same request the Statistics screen makes when you open it).
 * Kills this run = current value - value when the run started.
 */
public class KillCounter {
	private static final int REQUEST_EVERY_TICKS = 60; // every 3 seconds

	private int ticks = 0;
	/** "Mobs Killed" value when the run started, or -1 while waiting for a fresh value from the server. */
	private int startValue = -1;
	/** Ticks since the run started while waiting for the server's answer, or -1 when not waiting. */
	private int ticksSinceRunStart = -1;
	private int kills = 0;

	/** Called when a new run starts: the counter goes back to 0. */
	public void startRun() {
		kills = 0;
		startValue = -1;
		ticksSinceRunStart = 0;
		requestStats();
	}

	public int kills() {
		return kills;
	}

	public void onClientTick(Minecraft client) {
		if (client.player == null || client.getConnection() == null) return;
		if (++ticks >= REQUEST_EVERY_TICKS) {
			ticks = 0;
			requestStats();
		}

		int value = client.player.getStats().getValue(Stats.CUSTOM.get(Stats.MOB_KILLS));

		if (startValue < 0) {
			// Give the server a moment to answer the request sent at run start, then use that as the starting point.
			if (ticksSinceRunStart >= 0 && ++ticksSinceRunStart < 20) return;
			startValue = value;
			ticksSinceRunStart = -1;
		}
		kills = Math.max(0, value - startValue);
	}

	private static void requestStats() {
		ClientPacketListener connection = Minecraft.getInstance().getConnection();
		if (connection != null) {
			connection.send(new ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.REQUEST_STATS));
		}
	}
}
