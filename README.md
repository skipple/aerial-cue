# Aerial Cue

Audio cues for aerial fishing. While your bird is away, the plugin counts down the ticks
until the next fishing spot can be clicked: `tick_1` plays on the tick the spot is clickable
again, `tick_2` the tick before it, and so on, so the rising run lands right on your click.

Cues only play while the aerial fishing gloves are equipped. The wait is derived from your
distance to the spot, with frenzied (large) spots using their own fixed duration.

## Config

- **Sound profile** — which bundled set of five sounds to play (`Tone`, `Blip`).
- **Countdown length** — how many cues to play, 1 to 5. Lower it if the full run is too busy;
  at 1 you only get the cue on the tick you can click.
- **Volume** — playback volume of the cues.

## Adding a sound profile

Drop a directory of five wavs (`tick_1.wav` … `tick_5.wav`, where `tick_N` plays when N-1 ticks
remain) into `src/main/resources/sounds/`, then add a matching constant to `SoundProfile`.
