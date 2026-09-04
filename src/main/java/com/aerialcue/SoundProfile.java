package com.aerialcue;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * A set of five countdown sounds bundled with the plugin.
 *
 * Each profile is a directory under {@code /sounds} containing {@code tick_1.wav} through
 * {@code tick_5.wav}, where {@code tick_N} is played when N ticks remain before the fishing
 * spot can be clicked again. To add a profile, drop a new directory of five wavs into
 * {@code src/main/resources/sounds} and add a constant here.
 */
@Getter
@RequiredArgsConstructor
public enum SoundProfile
{
	TONE("Tone", "tone"),
	BLIP("Blip", "blip");

	private final String displayName;
	private final String directory;

	@Override
	public String toString()
	{
		return displayName;
	}
}
