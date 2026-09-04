package com.aerialcue;

import com.google.inject.Provides;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.Projectile;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.NpcDespawned;
import net.runelite.api.events.NpcSpawned;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.NpcID;
import net.runelite.api.gameval.SpotanimID;
import net.runelite.api.kit.KitType;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;

@Slf4j
@PluginDescriptor(
	name = "Aerial Cue",
	description = "Plays a countdown of audio cues while your bird is away aerial fishing",
	tags = {"aerial", "fishing", "hunter", "sound", "audio", "cue", "tick"}
)
public class AerialCuePlugin extends Plugin
{
	/** Number of clips a sound profile provides. Cue N plays when N-1 ticks remain. */
	static final int MAX_CUE_TICKS = 5;

	/**
	 * Ticks you must wait before the next spot can be clicked, by distance between you and the spot.
	 * Distances outside this range never produce a catch worth cueing.
	 */
	private static final Map<Integer, Integer> DISTANCE_TO_TICKS = new HashMap<>();

	static
	{
		DISTANCE_TO_TICKS.put(1, 0);
		DISTANCE_TO_TICKS.put(2, 0);
		DISTANCE_TO_TICKS.put(3, 1);
		DISTANCE_TO_TICKS.put(4, 1);
		DISTANCE_TO_TICKS.put(5, 2);
		DISTANCE_TO_TICKS.put(6, 3);
		DISTANCE_TO_TICKS.put(7, 3);
		DISTANCE_TO_TICKS.put(8, 4);
		DISTANCE_TO_TICKS.put(9, 4);
		DISTANCE_TO_TICKS.put(10, 5);
	}

	/** A frenzied (large) spot always takes the same time regardless of distance. */
	private static final int FRENZIED_TICKS = 2;

	@Inject
	private Client client;

	@Inject
	private AerialCueConfig config;

	@Inject
	private CueSoundPlayer soundPlayer;

	/** Tick on which each tracked catch becomes clickable again. */
	private final Map<Projectile, Integer> readyTicks = new HashMap<>();

	/** Centre tiles of frenzied spots currently in the scene. */
	private final Set<WorldPoint> frenziedPoints = new HashSet<>();

	private final Set<Projectile> seen = new HashSet<>();

	@Override
	protected void startUp()
	{
		soundPlayer.start();
		soundPlayer.load(config.soundProfile());
	}

	@Override
	protected void shutDown()
	{
		soundPlayer.stop();
		readyTicks.clear();
		frenziedPoints.clear();
		seen.clear();
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		// NPCs and projectiles do not survive a scene load, so drop everything tied to the old scene.
		if (event.getGameState() != GameState.LOGGED_IN)
		{
			readyTicks.clear();
			frenziedPoints.clear();
		}
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (AerialCueConfig.GROUP.equals(event.getGroup()) && "soundProfile".equals(event.getKey()))
		{
			soundPlayer.load(config.soundProfile());
		}
	}

	@Subscribe
	public void onNpcSpawned(NpcSpawned event)
	{
		WorldPoint centre = frenziedCentre(event.getNpc());
		if (centre != null)
		{
			frenziedPoints.add(centre);
		}
	}

	@Subscribe
	public void onNpcDespawned(NpcDespawned event)
	{
		WorldPoint centre = frenziedCentre(event.getNpc());
		if (centre != null)
		{
			frenziedPoints.remove(centre);
		}
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		if (!isGloveEquipped())
		{
			readyTicks.clear();
			return;
		}

		trackProjectiles();

		int tick = client.getTickCount();
		int soonest = Integer.MAX_VALUE;

		for (Integer readyTick : readyTicks.values())
		{
			int remaining = readyTick - tick;
			if (remaining >= 0 && remaining < soonest)
			{
				soonest = remaining;
			}
		}

		// Cue 1 lands on the tick the spot is clickable again, cue 2 the tick before it, and so on.
		int cue = soonest + 1;

		// One cue per tick, for whichever catch lands first, so overlapping catches cannot stack sounds.
		if (soonest != Integer.MAX_VALUE && cue <= Math.min(config.countdownTicks(), MAX_CUE_TICKS))
		{
			soundPlayer.play(cue, config.volume());
		}
	}

	/**
	 * Registers any newly thrown bird and retires spent catches.
	 */
	private void trackProjectiles()
	{
		Player local = client.getLocalPlayer();
		seen.clear();

		for (Projectile projectile : client.getProjectiles())
		{
			if (projectile.getId() != SpotanimID.AERIAL_FISHING_TRAVEL || projectile.getTargetActor() != local)
			{
				continue;
			}

			seen.add(projectile);

			if (readyTicks.containsKey(projectile))
			{
				continue;
			}

			// Registration happens on the tick the projectile is created, so the distance is measured
			// before the player has had a chance to move. The projectile's own remaining flight is not
			// a substitute: the bird is still in the air after the spot becomes clickable again.
			int waitTicks = waitTicks(projectile);

			if (waitTicks < 0)
			{
				continue;
			}

			int readyTick = client.getTickCount() + waitTicks;
			readyTicks.put(projectile, readyTick);

			log.debug("Catch registered: wait {} ticks, ready on tick {} ({} cycles of flight left)",
				waitTicks, readyTick, projectile.getRemainingCycles());
		}

		// An entry outlives its own projectile so a countdown always finishes, and is only dropped once
		// the projectile is gone, which is what stops a long-lived projectile being registered twice.
		int tick = client.getTickCount();
		readyTicks.entrySet().removeIf(entry -> tick > entry.getValue() && !seen.contains(entry.getKey()));
		seen.clear();
	}

	/**
	 * Ticks to wait before the spot is clickable again. Returns -1 for a distance that never produces
	 * a catch worth cueing.
	 */
	private int waitTicks(Projectile projectile)
	{
		WorldPoint source = projectile.getSourcePoint();

		if (frenziedPoints.contains(source))
		{
			return FRENZIED_TICKS;
		}

		return DISTANCE_TO_TICKS.getOrDefault(source.distanceTo2D(projectile.getTargetPoint()), -1);
	}

	/**
	 * The centre tile of a frenzied spot, matching the point its projectiles are thrown from, or null
	 * if the NPC is not a frenzied spot.
	 */
	private WorldPoint frenziedCentre(NPC npc)
	{
		if (npc.getId() != NpcID.FISHING_SPOT_AERIAL_LARGE)
		{
			return null;
		}

		int offset = npc.getComposition().getSize() / 2;
		return npc.getWorldLocation().dx(offset).dy(offset);
	}

	private boolean isGloveEquipped()
	{
		Player local = client.getLocalPlayer();
		if (local == null || local.getPlayerComposition() == null)
		{
			return false;
		}

		int weaponId = local.getPlayerComposition().getEquipmentId(KitType.WEAPON);
		return weaponId == ItemID.AERIAL_FISHING_GLOVES_BIRD || weaponId == ItemID.AERIAL_FISHING_GLOVES_NO_BIRD;
	}

	@Provides
	AerialCueConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(AerialCueConfig.class);
	}
}
