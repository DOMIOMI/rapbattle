"""
Generates the player animations for Player Animation Library (Bedrock / GeckoLib JSON).

Poses are written in a readable convention, in degrees:
  pitch  < 0  raises the part forward / up (arm up in front, head looks up)
  yaw    > 0  head / torso: turns to the character's left; arm: turns inward, across the body
  roll   > 0  for an arm: swings it outward, away from the body
Arms are mirrored, so the same numbers mean the same move for either arm.
The constants below turn that into the file's axes. Checked in game: file X- lifts an arm forward
(X+ sent the arms behind the back), file Y+ turns the head left, file Z+ swings the right arm out.

All battle animations are made at 120 bpm (one beat = 0.5 s, a bar = 2 s) and looped;
the game scales their speed to the battle's tempo.

Run: python tools/gen_player_anims.py
"""
import json
import os

SX, SY, SZ = 1, 1, 1  # readable -> file axes (pitch, yaw, roll)
OUT = os.path.join(os.path.dirname(__file__), '..', 'src', 'client', 'resources', 'assets', 'rapbattle',
                   'player_animations', 'battle.json')

B = 0.5  # one beat at 120 bpm


def rot(part, pitch=0.0, yaw=0.0, roll=0.0):
    if part == 'left_arm' or part == 'left_leg':
        roll = -roll
        yaw = -yaw
    return [round(SX * pitch, 2), round(SY * yaw, 2), round(SZ * roll, 2)]


def track(part, keys, loop_len):
    """keys: list of (time, pitch, yaw, roll). Adds the loop's closing frame so it wraps smoothly."""
    out = {}
    for t, p, y, r in keys:
        out[f'{t:.3f}'] = rot(part, p, y, r)
    if keys and keys[0][0] == 0 and f'{loop_len:.3f}' not in out:
        t, p, y, r = keys[0]
        out[f'{loop_len:.3f}'] = rot(part, p, y, r)
    return out


def accent(times, peak, rest, hold=0.12):
    """Snap to `peak` on each time, settle to `rest` shortly after: a hit on the beat, then a pose."""
    keys = []
    for t in times:
        keys.append((t, *peak))
        keys.append((t + hold, *rest))
    return keys


def anim(length, bones, positions=None):
    a = {'loop': True, 'animation_length': length, 'bones': {}}
    for part, keys in bones.items():
        a['bones'].setdefault(part, {})['rotation'] = track(part, sorted(keys), length)
    for part, keys in (positions or {}).items():
        out = {f'{t:.3f}': [x, y, z] for t, x, y, z in sorted(keys)}
        if keys and f'{length:.3f}' not in out:
            t, x, y, z = sorted(keys)[0]
            out[f'{length:.3f}'] = [x, y, z]
        a['bones'].setdefault(part, {})['position'] = out
    return a


beats = [i * B for i in range(4)]
A = {}

# ------------------------------------------------------------------ rapping: mic at the mouth, free hand tells the story
# Mic hand in front of the mouth: the arm forward and a bit up, turned in towards the chin.
mic = [(0, -100, 32, 0), (B * 0.5, -96, 30, 0), (B, -100, 32, 0), (B * 1.5, -96, 30, 0),
       (2 * B, -100, 32, 0), (2.5 * B, -96, 30, 0), (3 * B, -100, 32, 0), (3.5 * B, -96, 30, 0)]
A['rap_a'] = anim(2.0, {
    'right_arm': mic,
    # point, chop, up high, open palm: one gesture per beat
    'left_arm': [(0, -85, 18, 4), (0.12, -80, 15, 4),
                 (B, -55, 0, 8), (B + 0.12, -38, 0, 8),
                 (2 * B, -140, 10, 18), (2 * B + 0.12, -132, 10, 18),
                 (3 * B, -62, -10, 38), (3 * B + 0.12, -58, -10, 34)],
    'head': accent(beats, (14, 0, 0), (2, 0, 0)) + [],
    'torso': [(0, 6, 8, 0), (B, 2, 4, 0), (2 * B, 6, -8, 0), (3 * B, 2, -4, 0)],
    'right_leg': [(0, 6, 0, 0), (B, -4, 0, 0), (2 * B, 6, 0, 0), (3 * B, -4, 0, 0)],
    'left_leg': [(0, -4, 0, 0), (B, 6, 0, 0), (2 * B, -4, 0, 0), (3 * B, 6, 0, 0)],
}, {'body': [(0, 0, -1.0, 0), (0.12, 0, 0, 0), (B, 0, -1.0, 0), (B + 0.12, 0, 0, 0),
             (2 * B, 0, -1.0, 0), (2 * B + 0.12, 0, 0, 0), (3 * B, 0, -1.0, 0), (3 * B + 0.12, 0, 0, 0)]})

A['rap_b'] = anim(2.0, {
    'right_arm': mic,
    # the free hand rides the flow: low to high and back, wrist turning
    'left_arm': [(0, -30, 10, 12), (B, -105, 25, 6), (2 * B, -40, -5, 20), (3 * B, -120, 30, 4)],
    'head': [(0, 12, 6, 4), (0.15, 2, 6, 4), (B, 10, -2, 0), (B + 0.15, 0, -2, 0),
             (2 * B, 12, -6, -4), (2 * B + 0.15, 2, -6, -4), (3 * B, 10, 2, 0), (3 * B + 0.15, 0, 2, 0)],
    'torso': [(0, 8, 0, 5), (B, 3, 0, 0), (2 * B, 8, 0, -5), (3 * B, 3, 0, 0)],
    'right_leg': [(0, 5, 0, 0), (2 * B, -5, 0, 0)],
    'left_leg': [(0, -5, 0, 0), (2 * B, 5, 0, 0)],
}, {'body': [(0, 0, -1.2, 0), (0.12, 0, 0, 0), (2 * B, 0, -1.2, 0), (2 * B + 0.12, 0, 0, 0)]})

# ------------------------------------------------------------------ listening: arms crossed, nodding to the beat
crossed_r = (-52, 32, 0)
crossed_l = (-52, 32, 0)
A['listen'] = anim(2.0, {
    'right_arm': [(0, *crossed_r)],
    'left_arm': [(0, *crossed_l)],
    'head': accent(beats, (16, 0, 0), (0, 0, 0), hold=0.18),
    'torso': [(0, 0, 4, 0), (2 * B, 0, -4, 0)],
}, {'body': [(0, 0, -0.6, 0), (0.15, 0, 0, 0), (B, 0, -0.6, 0), (B + 0.15, 0, 0, 0),
             (2 * B, 0, -0.6, 0), (2 * B + 0.15, 0, 0, 0), (3 * B, 0, -0.6, 0), (3 * B + 0.15, 0, 0, 0)]})

# A teammate landed a line: both hands up, jumping on the beat.
A['hype'] = anim(1.0, {
    'right_arm': [(0, -170, 0, 22), (B, -150, 0, 30)],
    'left_arm': [(0, -150, 0, 30), (B, -170, 0, 22)],
    'head': [(0, -18, 0, 0), (B, -8, 0, 0)],
}, {'body': [(0, 0, 1.5, 0), (0.2, 0, 0, 0), (B, 0, 1.5, 0), (B + 0.2, 0, 0, 0)]})

# ------------------------------------------------------------------ winning (free 110 bpm feel, played at speed 1)
A['win_0'] = anim(1.0, {  # fist pump
    'right_arm': [(0, -175, 0, 8), (0.12, -165, 0, 8), (B, -135, 0, 12), (B + 0.12, -140, 0, 12)],
    'left_arm': [(0, -20, 0, 30)],
    'head': [(0, -20, 0, 0), (B, -12, 0, 0)],
    'right_leg': [(0, 8, 0, 0), (B, -8, 0, 0)],
    'left_leg': [(0, -8, 0, 0), (B, 8, 0, 0)],
}, {'body': [(0, 0, 1.2, 0), (0.15, 0, 0, 0), (B, 0, 1.2, 0), (B + 0.15, 0, 0, 0)]})

dab_r = {'head': (32, 35, 0), 'left_arm': (-100, 55, 0), 'right_arm': (-125, -10, 70)}
dab_l = {'head': (32, -35, 0), 'right_arm': (-100, 55, 0), 'left_arm': (-125, -10, 70)}
A['win_1'] = anim(2.0, {  # dab, switching sides
    'head': [(0, *dab_r['head']), (0.9, *dab_r['head']), (1.0, *dab_l['head']), (1.9, *dab_l['head'])],
    'right_arm': [(0, *dab_r['right_arm']), (0.9, *dab_r['right_arm']), (1.0, *dab_l['right_arm']), (1.9, *dab_l['right_arm'])],
    'left_arm': [(0, *dab_r['left_arm']), (0.9, *dab_r['left_arm']), (1.0, *dab_l['left_arm']), (1.9, *dab_l['left_arm'])],
    'torso': [(0, 8, -10, 0), (0.9, 8, -10, 0), (1.0, 8, 10, 0), (1.9, 8, 10, 0)],
})

A['win_2'] = anim(1.0, {  # flex: arms out and up, pumping
    'right_arm': [(0, -20, 0, 100), (B, -10, 0, 80)],
    'left_arm': [(0, -20, 0, 100), (B, -10, 0, 80)],
    'head': [(0, -12, 15, 0), (B, -12, -15, 0)],
    'torso': [(0, 0, 10, 0), (B, 0, -10, 0)],
}, {'body': [(0, 0, 0.8, 0), (0.15, 0, 0, 0), (B, 0, 0.8, 0), (B + 0.15, 0, 0, 0)]})

A['win_3'] = anim(1.0, {  # making it rain: the right hand flicks bills, the left holds the stack
    'right_arm': [(0, -70, -10, 0), (0.1, -150, 10, 0), (0.25, -70, -10, 0), (0.35, -150, 10, 0), (B, -70, -10, 0),
                  (B + 0.1, -150, 10, 0), (B + 0.25, -70, -10, 0), (B + 0.35, -150, 10, 0)],
    'left_arm': [(0, -60, 20, 0)],
    'head': [(0, -15, 0, 0)],
}, {'body': [(0, 0, 0.6, 0), (0.15, 0, 0, 0), (B, 0, 0.6, 0), (B + 0.15, 0, 0, 0)]})

# ------------------------------------------------------------------ losing: head down, slow shake, arms hanging
A['lose'] = anim(3.0, {
    'head': [(0, 38, 14, 0), (1.5, 38, -14, 0)],
    'right_arm': [(0, -8, 0, 4)],
    'left_arm': [(0, -8, 0, 4), (1.0, -18, 0, 10), (1.6, -8, 0, 4)],
    'torso': [(0, 12, 0, 0)],
})

os.makedirs(os.path.dirname(OUT), exist_ok=True)
with open(OUT, 'w', encoding='utf-8') as f:
    json.dump({'format_version': '1.8.0', 'animations': A}, f, indent=1)
print('wrote', os.path.normpath(OUT), len(A), 'animations')
