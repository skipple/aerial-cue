package com.aerialcue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
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
 *
 * Fired verbatim, a cue is the same bytes at the same level thousands of times an hour, and exact
 * repetition wears on the ear independently of how pleasant the sound is. Two optional nudges break
 * it: a random gain offset per play, and a random pick from detuned copies of each clip built at
 * load time. Both are sized to sit under what a listener notices as a change.
 */
@Slf4j
@Singleton
class CueSoundPlayer
{
	/** Random gain offset per cue, in dB either side of the configured volume. Level JND is about 1 dB. */
	private static final float GAIN_HUMANIZE_DB = 1f;

	/**
	 * Copies of each clip per cue, index 0 being the untouched original and the rest spread evenly
	 * across ±{@link #PITCH_HUMANIZE_CENTS}. Pitch JND for a complex tone is 5-10 cents; the cues sit
	 * 200 cents apart at their closest, so the countdown's notes cannot be confused for one another.
	 */
	private static final int PITCH_VARIANTS = 7;
	private static final double PITCH_HUMANIZE_CENTS = 4;

	/** Field offsets in a canonical 44-byte PCM wav header. */
	private static final int WAV_HEADER_LENGTH = 44;
	private static final int WAV_SAMPLE_RATE = 24;
	private static final int WAV_BYTE_RATE = 28;
	private static final int WAV_BLOCK_ALIGN = 32;

	@Inject
	private AudioPlayer audioPlayer;

	/** Opening a line blocks, so playback never happens on the client thread. */
	private ExecutorService executor;

	/**
	 * Raw wav data for the loaded profile, indexed by cue number minus one and then by pitch variant.
	 * Null entries failed to load; a clip whose header could not be detuned has only its original.
	 */
	private volatile byte[][][] samples;
	private SoundProfile requested;

	/** Variant last played per cue, so the same waveform never fires twice in a row. Client thread only. */
	private final int[] lastVariant = new int[AerialCuePlugin.MAX_CUE_TICKS];

	/** Set once the mixer refuses a detuned clip's sample rate, after which only originals are played. */
	private volatile boolean pitchUnsupported;

	void start()
	{
		pitchUnsupported = false;
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

			byte[][][] loaded = new byte[AerialCuePlugin.MAX_CUE_TICKS][][];
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

					loaded[i] = variants(readFully(in));
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
	 *
	 * @param humanizeGain  nudge the level by up to ±{@link #GAIN_HUMANIZE_DB}
	 * @param humanizePitch play a random detuned copy instead of the same waveform every time
	 */
	void play(int cue, int volume, boolean humanizeGain, boolean humanizePitch)
	{
		byte[][][] current = samples;
		ExecutorService executor = this.executor;

		if (current == null || executor == null || volume <= 0 || cue < 1 || cue > current.length)
		{
			return;
		}

		byte[][] variants = current[cue - 1];
		if (variants == null)
		{
			return;
		}

		int variant = 0;
		if (humanizePitch && variants.length > 1 && !pitchUnsupported)
		{
			// Draw from every variant but the one this cue played last.
			int last = lastVariant[cue - 1];
			variant = ThreadLocalRandom.current().nextInt(variants.length - 1);
			if (variant >= last)
			{
				variant++;
			}
			lastVariant[cue - 1] = variant;
		}

		byte[] sample = variants[variant];
		byte[] original = variants[0];

		// Matches the client's own notification volume curve: linear 1-100 to decibel gain.
		float gain = (float) Math.log10(Math.min(volume, 100) / 100f) * 20f;

		if (humanizeGain)
		{
			// Never above 0 dB: MASTER_GAIN rejects values past its range, and AudioPlayer answers that
			// by playing at full volume, ignoring the setting entirely. Mirroring the offset rather than
			// clamping it keeps a full-volume cue varying instead of pinning half its plays to exactly 0.
			float jitter = (ThreadLocalRandom.current().nextFloat() * 2f - 1f) * GAIN_HUMANIZE_DB;
			gain = gain + jitter > 0 ? gain - jitter : gain + jitter;
		}

		float finalGain = gain;

		try
		{
			executor.execute(() ->
			{
				try
				{
					audioPlayer.play(new ByteArrayInputStream(sample), finalGain);
					return;
				}
				catch (Exception e)
				{
					if (sample == original)
					{
						log.warn("Unable to play cue {}", cue, e);
						return;
					}

					log.debug("Mixer rejected detuned cue {}", cue, e);
				}

				// A mixer that will not open a line at the detuned rate would otherwise silence the cue.
				// If the original plays, the rate was the problem, and there is no point asking again.
				try
				{
					audioPlayer.play(new ByteArrayInputStream(original), finalGain);
					pitchUnsupported = true;
					log.warn("Mixer rejected detuned cue sounds; pitch variation disabled for this session");
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

	/**
	 * The original clip followed by its detuned copies, or the original alone if its header is not
	 * one {@link #detune} understands.
	 */
	private static byte[][] variants(byte[] original)
	{
		byte[][] variants = new byte[PITCH_VARIANTS][];
		variants[0] = original;

		for (int i = 1; i < PITCH_VARIANTS; i++)
		{
			// Evenly spaced from -max to +max, skipping 0 since index 0 already is the original.
			double cents = -PITCH_HUMANIZE_CENTS + 2 * PITCH_HUMANIZE_CENTS * (i - 1) / (PITCH_VARIANTS - 2);
			byte[] detuned = detune(original, cents);

			if (detuned == null)
			{
				return new byte[][]{original};
			}

			variants[i] = detuned;
		}

		return variants;
	}

	/**
	 * A copy of a PCM wav whose header claims a sample rate the given number of cents away from the
	 * original. The sample data is untouched: the decoder plays it faster or slower, which shifts
	 * pitch and length together. A few cents moves the length by well under a millisecond, so the
	 * cue still lands on its tick.
	 *
	 * Only the canonical 44-byte layout is handled, which is what the generator writes. Returns null
	 * for anything else rather than guessing at where the rate field is.
	 */
	private static byte[] detune(byte[] wav, double cents)
	{
		if (wav.length <= WAV_HEADER_LENGTH)
		{
			return null;
		}

		ByteBuffer header = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
		boolean canonical = header.getInt(0) == 0x46464952 // "RIFF"
			&& header.getInt(8) == 0x45564157 // "WAVE"
			&& header.getInt(12) == 0x20746d66 // "fmt "
			&& header.getInt(16) == 16 // PCM fmt chunk, so the data chunk starts at 36
			&& header.getShort(20) == 1; // uncompressed

		if (!canonical)
		{
			return null;
		}

		int rate = header.getInt(WAV_SAMPLE_RATE);
		int blockAlign = header.getShort(WAV_BLOCK_ALIGN);
		int detunedRate = (int) Math.round(rate * Math.pow(2, cents / 1200));

		byte[] copy = wav.clone();
		ByteBuffer.wrap(copy).order(ByteOrder.LITTLE_ENDIAN)
			.putInt(WAV_SAMPLE_RATE, detunedRate)
			.putInt(WAV_BYTE_RATE, detunedRate * blockAlign);
		return copy;
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
