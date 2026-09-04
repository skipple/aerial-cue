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
	MARIMBA("Marimba", "marimba"),
	KALIMBA("Kalimba", "kalimba"),
	LOG_DRUM("Log drum", "logdrum"),
	GLASS("Glass", "glass"),
	PLUCK("Pluck", "pluck"),
	/** Deliberately longer than a game tick, so consecutive cues ring over one another. */
	PLUCK_LONG("Pluck (long)", "plucklong"),
	GUITAR("Guitar chords", "guitar"),
	DRUMPAD("Drum pad", "drumpad"),
	SUB_808("808", "sub808");

	private final String displayName;
	private final String directory;

	@Override
	public String toString()
	{
		return displayName;
	}
}
