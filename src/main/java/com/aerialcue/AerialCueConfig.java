package com.aerialcue;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Range;
import net.runelite.client.config.Units;

@ConfigGroup(AerialCueConfig.GROUP)
public interface AerialCueConfig extends Config
{
	String GROUP = "aerial-cue";

	@ConfigItem(
		position = 0,
		keyName = "soundProfile",
		name = "Sound profile",
		description = "Which set of countdown sounds to play."
	)
	default SoundProfile soundProfile()
	{
		return SoundProfile.MARIMBA;
	}

	@ConfigItem(
		position = 1,
		keyName = "countdownTicks",
		name = "Countdown length",
		description = "How many cues to play, counting back from the tick the spot is clickable again. At 5 a"
			+ " full-distance catch plays all five; at 1 only the cue on the tick you can click plays."
	)
	@Range(min = 1, max = AerialCuePlugin.MAX_CUE_TICKS)
	@Units(Units.TICKS)
	default int countdownTicks()
	{
		return AerialCuePlugin.MAX_CUE_TICKS;
	}

	@ConfigItem(
		position = 2,
		keyName = "volume",
		name = "Volume",
		description = "Playback volume of the countdown sounds."
	)
	@Range(max = 100)
	@Units(Units.PERCENT)
	default int volume()
	{
		return 100;
	}
}
