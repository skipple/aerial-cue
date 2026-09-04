package com.aerialcue;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.inject.Singleton;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.FloatControl;
import lombok.extern.slf4j.Slf4j;

/**
 * Holds a {@link SoundProfile}'s clips open and ready to fire.
 *
 * Cues have to land on the game tick that scheduled them, so playback cannot afford to open an
 * audio line: allocating and opening a line costs tens to hundreds of milliseconds and is prone to
 * stalling, which is audible against a 600ms tick. Every clip is therefore decoded and opened once,
 * up front and off the client thread, leaving playback as a rewind and a start.
 */
@Slf4j
@Singleton
class CueSoundPlayer
{
	/** Opening and closing lines blocks, so it never happens on the client thread. */
	private ExecutorService executor;

	/** Open clips for the loaded profile, indexed by cue number minus one. Null entries failed to open. */
	private volatile Clip[] clips;
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
		Clip[] open = clips;
		clips = null;
		requested = null;

		if (executor != null)
		{
			// Hand the close off before shutdown so it does not block the client thread; shutdownNow
			// only interrupts, and closing a line does not respond to interrupts anyway.
			executor.execute(() -> close(open));
			executor.shutdown();
			executor = null;
		}
		else
		{
			close(open);
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
			Clip[] previous = clips;
			clips = null;
			close(previous);

			Clip[] opened = new Clip[AerialCuePlugin.MAX_CUE_TICKS];
			for (int i = 0; i < opened.length; i++)
			{
				String path = "/sounds/" + profile.getDirectory() + "/tick_" + (i + 1) + ".wav";
				try (InputStream in = CueSoundPlayer.class.getResourceAsStream(path))
				{
					if (in == null)
					{
						log.warn("Missing cue sound {}", path);
						continue;
					}

					try (AudioInputStream audio = AudioSystem.getAudioInputStream(new BufferedInputStream(in)))
					{
						Clip clip = AudioSystem.getClip();
						clip.open(audio);
						opened[i] = clip;
					}
				}
				catch (Exception e)
				{
					log.warn("Unable to open cue sound {}", path, e);
				}
			}

			clips = opened;
			log.debug("Opened sound profile {}", profile);
		});
	}

	/**
	 * Fires the given cue at a linear 0-100 volume. Safe to call from the client thread.
	 */
	void play(int cue, int volume)
	{
		Clip[] current = clips;
		if (current == null || volume <= 0 || cue < 1 || cue > current.length)
		{
			return;
		}

		Clip clip = current[cue - 1];
		if (clip == null)
		{
			return;
		}

		try
		{
			// Matches the client's own notification volume curve: linear 1-100 to decibel gain.
			setGain(clip, (float) Math.log10(Math.min(volume, 100) / 100f) * 20f);

			clip.stop();
			clip.setFramePosition(0);
			clip.start();
		}
		catch (Exception e)
		{
			log.warn("Unable to play cue {}", cue, e);
		}
	}

	private static void setGain(Clip clip, float gain)
	{
		if (!clip.isControlSupported(FloatControl.Type.MASTER_GAIN))
		{
			return;
		}

		FloatControl control = (FloatControl) clip.getControl(FloatControl.Type.MASTER_GAIN);
		control.setValue(Math.max(control.getMinimum(), Math.min(control.getMaximum(), gain)));
	}

	private static void close(Clip[] clips)
	{
		if (clips == null)
		{
			return;
		}

		for (Clip clip : clips)
		{
			if (clip != null)
			{
				clip.close();
			}
		}
	}
}
