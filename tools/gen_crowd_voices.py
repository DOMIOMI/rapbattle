"""
Crowd variety: individual fans and more hall takes, made with ElevenLabs.

  - Solo voices: 6 different people, each with their own boos and cheers (TTS, Russian voices). A fan in the crowd keeps
    one voice for the whole battle, so one boos like this and another like that.
    -> sounds/solo/<kind>_v<voice>_<n>.ogg, events "solo.<kind>.v<voice>" (Minecraft picks a random take each time).
  - Hall takes: several differently described recordings per reaction (sound effects), so the hall never loops.
    -> sounds/crowd/<kind><n>.ogg, events "crowd.<kind>".

Run: python tools/gen_crowd_voices.py <path-to-elevenlabs-key-file>
"""
import json
import os
import subprocess
import sys
import urllib.request

ROOT = os.path.join(os.path.dirname(__file__), '..', 'src', 'main', 'resources', 'assets', 'rapbattle')
SOUNDS_JSON = os.path.join(ROOT, 'sounds.json')
SOLO = os.path.join(ROOT, 'sounds', 'solo')
HALL = os.path.join(ROOT, 'sounds', 'crowd')
TMP = os.path.join(os.path.dirname(__file__), '..', 'build', 'voices-tmp')

VOICES = ['ouyTiWqmHA5WI5bbX7zj', 'XuEV9VY3VUASYgJVNBh0', 'USJ5Rjf2LwNEcibI3sN4', 'QSM8BzHrVEW9vi9xYs4U',
          'TUQNWEvVPBLzMBSVDPUA', 'wUndevsXFk0ArF7vJ61U']

SOLO_LINES = {
    'boo': ['Буууу!', 'Фууу!', 'Слабак!', 'Мимо, мимо!', 'Бу-у-у, позор!', 'Фу-у, уходи!', 'Скучно-о-о!', 'Не верю!'],
    'cheer': ['Йееес!', 'Да-а-а-а!', 'У-у-у-у-у!', 'Жги!', 'Вот так!', 'Давай-давай!', 'Хо-хо-хо!', 'Красота-а!', 'Ещё!'],
}
PER_VOICE = {'boo': 4, 'cheer': 5}

HALL_TAKES = {
    'cheer': ['Big excited crowd at an underground hip-hop rap battle cheering and screaming yeah, loud, close, energetic',
              'Wild crowd going crazy at a rap show, screaming and cheering, people jumping',
              'Crowd cheering and clapping after a great rap line, shouts of yeah and whoa',
              'Small rowdy crowd cheering loudly in a basement club, hoots and shouts',
              'Crowd roaring in approval at a rap battle, chanting and whooping'],
    'boo': ['Crowd booing loudly at a rap battle, strong disapproval, jeering',
            'Angry crowd booing and whistling in disapproval, jeers and shouts',
            'Disappointed crowd groaning and booing, long drawn out boo',
            'Crowd booing and laughing at a loser, mocking jeers',
            'Rowdy crowd booing someone off stage, loud and harsh'],
    'ooh': ['Crowd at a rap battle reacting with a loud collective Ooooh to a savage punchline, surprised and impressed',
            'Crowd gasping and shouting ohhh at a brutal diss, excited',
            'Crowd reacting ooooh damn to a punchline, laughing and shouting',
            'Shocked crowd going oooh in unison, then excited chatter'],
    'whistle': ['Crowd whistling loudly with fingers and cheering at a concert',
                'Several people whistling sharply with fingers in a cheering crowd',
                'Loud wolf whistles and cheers from a crowd'],
    'laugh': ['Crowd laughing and mocking someone at a rap battle after an embarrassing fail',
              'Crowd bursting into laughter and pointing, mocking laughs',
              'Crowd chuckling and jeering at a weak rap line'],
    'gasp': ['Crowd gasping in shock, short', 'Crowd gasping and murmuring in disbelief, short'],
    'applause': ['Big applause and cheering crowd at the end of a rap battle, celebration',
                 'Thunderous applause with whistles and screams, crowd celebrating a winner',
                 'Crowd clapping and chanting for the winner, energetic'],
    'hype': ['Hyped crowd at a hip-hop show shouting hey hey hey in rhythm',
             'Crowd clapping on the beat and shouting ho ho ho in rhythm',
             'Crowd chanting rhythmically and stomping, getting hyped before a rap battle'],
}
HALL_LEN = {'cheer': 3.0, 'boo': 3.0, 'ooh': 2.0, 'whistle': 2.5, 'laugh': 2.5, 'gasp': 1.5, 'applause': 4.0, 'hype': 3.0}


def post(url, key, body):
    req = urllib.request.Request(url, data=json.dumps(body).encode(), headers={'xi-api-key': key, 'Content-Type': 'application/json'},
                                 method='POST')
    return urllib.request.urlopen(req, timeout=180).read()


def to_ogg(mp3, ogg, af):
    subprocess.run(['ffmpeg', '-y', '-loglevel', 'error', '-i', mp3, '-af', af, '-ac', '1', '-ar', '44100', '-c:a', 'libvorbis',
                    '-q:a', '4', ogg], check=True)


def main():
    key = open(sys.argv[1]).read().strip()
    for d in (SOLO, HALL, TMP):
        os.makedirs(d, exist_ok=True)
    tmp = os.path.join(TMP, 'x.mp3')
    sounds = json.load(open(SOUNDS_JSON, encoding='utf-8'))

    # Solo fans: each voice gets its own subset of lines, shifted so voices do not all say the same thing.
    for kind, lines in SOLO_LINES.items():
        for v, voice in enumerate(VOICES):
            names = []
            for n in range(PER_VOICE[kind]):
                text = lines[(v * 3 + n) % len(lines)]
                ogg = os.path.join(SOLO, f'{kind}_v{v}_{n + 1}.ogg')
                if not os.path.exists(ogg):
                    open(tmp, 'wb').write(post(f'https://api.elevenlabs.io/v1/text-to-speech/{voice}?output_format=mp3_44100_128', key, {
                        'text': text, 'model_id': 'eleven_multilingual_v2',
                        'voice_settings': {'stability': 0.2, 'similarity_boost': 0.7, 'style': 0.85, 'use_speaker_boost': True}}))
                    to_ogg(tmp, ogg, 'highpass=f=120,aecho=0.8:0.4:50|95:0.2|0.1,volume=-1dB')
                    print('solo', kind, v, text)
                names.append({'name': f'rapbattle:solo/{kind}_v{v}_{n + 1}', 'attenuation_distance': 28})
            sounds[f'solo.{kind}.v{v}'] = {'sounds': names}

    # Hall: keep the existing takes, add the new ones after them.
    for kind, prompts in HALL_TAKES.items():
        for i, prompt in enumerate(prompts, 1):
            ogg = os.path.join(HALL, f'{kind}{i}.ogg')
            if os.path.exists(ogg):
                continue
            open(tmp, 'wb').write(post('https://api.elevenlabs.io/v1/sound-generation?output_format=mp3_44100_128', key,
                                       {'text': prompt, 'duration_seconds': HALL_LEN[kind], 'prompt_influence': 0.45}))
            d = HALL_LEN[kind]
            to_ogg(tmp, ogg, f'volume=-3dB,afade=t=in:d=0.05,afade=t=out:st={max(0, d - 0.45):.2f}:d=0.45')
            print('hall', kind, i)
        files = sorted(f for f in os.listdir(HALL) if f.startswith(kind) and f[len(kind):-4].isdigit())
        sounds[f'crowd.{kind}'] = {'subtitle': f'subtitles.rapbattle.crowd.{kind}',
                                   'sounds': [{'name': f'rapbattle:crowd/{f[:-4]}', 'attenuation_distance': 40} for f in files]}
    if os.path.exists(tmp):
        os.remove(tmp)
    json.dump(sounds, open(SOUNDS_JSON, 'w', encoding='utf-8'), indent=2)
    print('done')


main()
