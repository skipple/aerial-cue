package com.aerialcue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.audio.AudioPlayer;

/**
 * Holds a {@link SoundProfile}'s clips in memory, ready to fire.
 *
 * Cues have to land on the game tick that scheduled them, so playback cannot afford to read a wav
 * off the classpath first: inflating it out of the jar is prone to stalling, which is audible
 * against a 600ms tick. Every clip is therefore read into a byte array once, up front and off the
 * client thread, leaving playback as a handoff to {@link AudioPlayer} on the audio thread.
 */
@Slf4j
@Singleton
class CueSoundPlayer
{
	@Inject
	private AudioPlayer audioPlayer;

	/** Opening a line blocks, so playback never happens on the client thread. */
	private ExecutorService executor;

	/** Raw wav data for the loaded profile, indexed by cue number minus one. Null entries failed to load. */
	private volatile byte[][] samples;
	private SoundProfile requested;

	void start()
	{
		executor = Executors.newSingleThreadExecutor(r ->
		{
			Thread t = new Thread(r, "aerial-cue-audio");
			t.setDaemon(true);
			return t;
		});
	}

	void stop()
	{
		samples = null;
		requested = null;

		if (executor != null)
		{
			executor.shutdownNow();
			executor = null;
		}
	}

	void load(SoundProfile profile)
	{
		if (profile == requested || executor == null)
		{
			return;
		}

		requested = profile;

		executor.execute(() ->
		{
			samples = null;

			byte[][] loaded = new byte[AerialCuePlugin.MAX_CUE_TICKS][];
			for (int i = 0; i < loaded.length; i++)
			{
				String path = "/sounds/" + profile.getDirectory() + "/tick_" + (i + 1) + ".wav";
				try (InputStream in = CueSoundPlayer.class.getResourceAsStream(path))
				{
					if (in == null)
					{
						log.warn("Missing cue sound {}", path);
						continue;
					}

					loaded[i] = readFully(in);
				}
				catch (Exception e)
				{
					log.warn("Unable to load cue sound {}", path, e);
				}
			}

			samples = loaded;
			log.debug("Loaded sound profile {}", profile);
		});
	}

	/**
	 * Fires the given cue at a linear 0-100 volume. Safe to call from the client thread.
	 */
	void play(int cue, int volume)
	{
		byte[][] current = samples;
		ExecutorService executor = this.executor;

		if (current == null || executor == null || volume <= 0 || cue < 1 || cue > current.length)
		{
			return;
		}

		byte[] sample = current[cue - 1];
		if (sample == null)
		{
			return;
		}

		// Matches the client's own notification volume curve: linear 1-100 to decibel gain.
		float gain = (float) Math.log10(Math.min(volume, 100) / 100f) * 20f;

		try
		{
			executor.execute(() ->
			{
				try
				{
					audioPlayer.play(new ByteArrayInputStream(sample), gain);
				}
				catch (Exception e)
				{
					log.warn("Unable to play cue {}", cue, e);
				}
			});
		}
		catch (Exception e)
		{
			log.debug("Dropped cue {}", cue, e);
		}
	}

	private static byte[] readFully(InputStream in) throws Exception
	{
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		byte[] buffer = new byte[8192];

		for (int read; (read = in.read(buffer)) != -1; )
		{
			out.write(buffer, 0, read);
		}

		return out.toByteArray();
	}
}
