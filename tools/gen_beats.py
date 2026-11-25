"""
Battle beats: original instrumentals made with ElevenLabs Music, then fitted to the battle grid.

For every track:
  1. generate ~60 s of instrumental hip-hop in a style and tempo;
  2. measure its real tempo and the first downbeat from the drum onsets (numpy only);
  3. time-stretch it so one beat is a whole number of milliseconds (the battle clock is in ms);
  4. cut from the first downbeat to a whole number of bars, so it loops exactly on the grid.
Writes assets/rapbattle/sounds/beat/<id>.ogg, sounds.json entries "beat.<id>" and battle/BeatTracks.java.

Run: python tools/gen_beats.py <path-to-elevenlabs-key-file>
"""
import json
import math
import os
import subprocess
import sys
import urllib.request

import numpy as np

ROOT = os.path.join(os.path.dirname(__file__), '..', 'src', 'main')
SND = os.path.join(ROOT, 'resources', 'assets', 'rapbattle', 'sounds', 'beat')
SOUNDS_JSON = os.path.join(ROOT, 'resources', 'assets', 'rapbattle', 'sounds.json')
JAVA = os.path.join(ROOT, 'java', 'dev', 'rapbattle', 'battle', 'BeatTracks.java')
RAW = os.path.join(os.path.dirname(__file__), '..', 'build', 'beats-raw')

TRACKS = [
    # Pure beats: drums and bass only, no melody at all.
    ('breaks', 'брейкбит', 84, 'Hard aggressive boom bap drum break for battle rap, only drums and deep bass, no melody, no chords, '
                               'punchy heavy kick, loud cracking snare, crisp hi-hats, raw and energetic'),
    ('knock', 'жёсткие ударные', 92, 'Hard knocking hip-hop drums with a heavy 808 bass line only, no melody, no chords, '
                                     'aggressive punchy kick and snare, driving energetic groove'),
    ('stomp', 'топот', 98, 'Stomping aggressive hip-hop beat, only drums, claps and distorted bass, no melody, no chords, '
                           'very hard hitting and energetic, crowd-pumping'),
    ('trapdrums', 'трэп-ударные', 104, 'Hard trap drums and booming 808 only, no melody, no chords, fast rolling hi-hats, '
                                       'aggressive clap, energetic and loud'),
    # Energetic beats with a melody: never calm.
    ('battle', 'баттл', 88, 'Aggressive energetic battle rap beat, hard boom bap drums, intense brass stabs, tense and driving, '
                            'high energy, no calm parts'),
    ('streets', 'улица', 96, 'Aggressive energetic street rap beat, hard punchy drums, intense staccato strings, menacing, '
                             'driving high energy'),
    ('trap', 'трэп', 100, 'Hard aggressive trap beat, booming distorted 808, fast hi-hats, dark intense synth stabs, high energy'),
    ('drill', 'дрилл', 106, 'Aggressive energetic drill beat, sliding 808 bass, hard syncopated drums, intense dark piano stabs, '
                            'driving'),
    ('boss', 'босс', 116, 'Epic aggressive hip-hop beat for a boss battle, huge hard drums with a loud kick on every beat and a big snare on 2 and 4, '
                          'distorted 808, intense choir hits, very high energy, steady straight rhythm'),
]


def compose(key, prompt, bpm):
    body = json.dumps({
        'prompt': f'{prompt}. Instrumental only, no vocals. Steady {bpm} BPM, 4/4, strong clear beat from the very first second, '
                  f'consistent groove all the way through, loopable.',
        'music_length_ms': 60000,
        'force_instrumental': True,
    }).encode()
    req = urllib.request.Request('https://api.elevenlabs.io/v1/music?output_format=mp3_44100_192', data=body,
                                 headers={'xi-api-key': key, 'Content-Type': 'application/json'}, method='POST')
    return urllib.request.urlopen(req, timeout=600).read()


def decode(path, sr=22050):
    raw = subprocess.run(['ffmpeg', '-loglevel', 'error', '-i', path, '-ac', '1', '-ar', str(sr), '-f', 'f32le', '-'],
                         capture_output=True, check=True).stdout
    return np.frombuffer(raw, dtype=np.float32), sr


def onsets(y, sr, hop=256, n=1024):
    win = np.hanning(n).astype(np.float32)
    frames = 1 + (len(y) - n) // hop
    idx = np.arange(n)[None, :] + hop * np.arange(frames)[:, None]
    spec = np.abs(np.fft.rfft(y[idx] * win, axis=1))
    logs = np.log1p(10 * spec)
    flux = np.maximum(0, np.diff(logs, axis=0))
    freqs = np.fft.rfftfreq(n, 1 / sr)
    full = flux.sum(axis=1)
    low = flux[:, freqs < 150].sum(axis=1)
    full = full - np.convolve(full, np.ones(16) / 16, mode='same')  # local mean removed
    return np.maximum(0, full), np.maximum(0, low), sr / hop


def tempo_and_downbeat(path, want):
    y, sr = decode(path)
    o, low, fps = onsets(y, sr)
    ac = np.correlate(o, o, mode='full')[len(o) - 1:]

    def ac_at(lag):
        i = int(lag)
        f = lag - i
        return 0 if i + 1 >= len(ac) else ac[i] * (1 - f) + ac[i + 1] * f

    best, best_bpm = -1, want
    for bpm in np.arange(want * 0.9, want * 1.1, 0.02):
        lag = fps * 60 / bpm
        sc = sum(ac_at(lag * k) for k in (1, 2, 4, 8))
        if sc > best:
            best, best_bpm = sc, bpm
    period = fps * 60 / best_bpm
    # Phase: where the beat grid lands on the most kicks (low band) with a little of the full onsets; hi-hats on
    # the eighths would otherwise pull the grid half a beat off.
    t = np.arange(len(o))
    lown = low / max(1e-9, low.mean())
    fulln = o / max(1e-9, o.mean())
    best_ph, best_sc = 0.0, -1
    for ph in np.arange(0, period, 0.25):
        pos = ph + period * np.arange(int((len(o) - ph) / period))
        sc = np.interp(pos, t, lown).sum() + 0.05 * np.interp(pos, t, fulln).sum()
        if sc > best_sc:
            best_sc, best_ph = sc, ph
    # Downbeat: the beat of the bar with the most kick (low-band) energy.
    pos = best_ph + period * np.arange(int((len(o) - best_ph) / period))
    kick = np.interp(pos, t, low)
    j = int(np.argmax([kick[k::4].sum() for k in range(4)]))
    first = (best_ph + j * period) / fps
    return best_bpm, first, len(y) / sr


def kick_offset(path, beat_ms):
    """Where the kicks sit relative to the grid of a fitted file, in seconds (-half beat .. +half beat)."""
    y, sr = decode(path)
    _, low, fps = onsets(y, sr)
    period = beat_ms / 1000 * fps
    t = np.arange(len(low))
    phases = np.arange(0, period, 0.25)
    scores = [np.interp(ph + period * np.arange(int((len(low) - ph) / period)), t, low).mean() for ph in phases]
    best = phases[int(np.argmax(scores))]
    if best > period / 2:
        best -= period
    return best / fps


def main():
    key = open(sys.argv[1]).read().strip()
    os.makedirs(SND, exist_ok=True)
    os.makedirs(RAW, exist_ok=True)
    made = []
    for tid, name, bpm, prompt in TRACKS:
        raw = os.path.join(RAW, f'{tid}.mp3')
        if not os.path.exists(raw):
            open(raw, 'wb').write(compose(key, prompt, bpm))
            print('composed', tid)
        est, first, dur = tempo_and_downbeat(raw, bpm)
        beat_ms = round(60000 / est)
        target = 60000 / beat_ms
        factor = target / est  # >1 speeds up
        start = first / factor
        out = os.path.join(SND, f'{tid}.ogg')
        # Cut, then check where the kicks land in the result; if the grid is off, move the cut and cut again.
        for attempt in range(3):
            bars = int(((dur / factor) - start) / (4 * beat_ms / 1000))
            bars = max(4, bars - bars % 4)
            length = bars * 4 * beat_ms / 1000
            subprocess.run(['ffmpeg', '-y', '-loglevel', 'error', '-i', raw, '-af',
                            f'atempo={factor:.6f},atrim=start={start:.4f}:duration={length:.4f},asetpts=PTS-STARTPTS,'
                            f'afade=t=in:d=0.004,afade=t=out:st={length - 0.01:.4f}:d=0.01,volume=-2dB',
                            '-ar', '44100', '-ac', '2', '-c:a', 'libvorbis', '-q:a', '5', out], check=True)
            off = kick_offset(out, beat_ms)
            if abs(off) < 0.025:
                break
            start += off if off > 0 else off + beat_ms / 1000
            print(f'  {tid}: kicks {off * 1000:.0f} ms off the grid, re-cutting')
        print(f'{tid}: measured {est:.2f} bpm -> {target:.3f} ({beat_ms} ms/beat), downbeat at {first:.3f}s, {bars} bars, {length:.1f}s')
        made.append((tid, name, beat_ms, bars))

    sounds = json.load(open(SOUNDS_JSON, encoding='utf-8'))
    for tid, *_ in made:
        sounds[f'beat.{tid}'] = {'sounds': [{'name': f'rapbattle:beat/{tid}', 'stream': False}]}
    json.dump(sounds, open(SOUNDS_JSON, 'w', encoding='utf-8'), indent=2)

    rows = ',\n'.join(f'\t\tnew Track("{tid}", "{name}", {beat_ms}, {bars})' for tid, name, beat_ms, bars in made)
    open(JAVA, 'w', encoding='utf-8').write(f'''package dev.rapbattle.battle;

// Generated by tools/gen_beats.py from the fitted tracks. Do not edit by hand.

import java.util.List;

/** The real beats: each loops exactly on the battle grid (one beat = beatMs, the file is a whole number of bars). */
public final class BeatTracks {{
	private BeatTracks() {{}}

	public record Track(String id, String name, int beatMs, int bars) {{
		public double bpm() {{ return 60000.0 / beatMs; }}
	}}

	public static final List<Track> ALL = List.of(
{rows});

	public static Track byId(String id) {{
		for (Track t : ALL) if (t.id().equals(id)) return t;
		return null;
	}}
}}
''')
    print('done')


main()
