"""
Builds the theme-song test episodes for the mock server (`server.py --themes`).

Four 17-minute episodes of one made-up show, each with the same opening theme after a cold open
of a different length, and the same ending theme near the end, between talk (Windows' speech
synthesiser), other music and loud bursts, as a real episode has. What is on screen says where
the themes really are, so a screenshot shows whether the app skipped the right thing:

  media/theme_ep1.mp4 … theme_ep4.mp4
  media/themes.json   where each episode's opening and ending are, in ms (the server's intro
                      database stand-in reads it)
  media/tts/theme_epN.decoded.wav   each episode's audio after AAC, for core's
                      ThemesOnRealAudioTest

Run: python make_themes.py   (Windows, with ffmpeg on PATH or in D:/tools/ffmpeg/bin)
"""
import json
import os
import subprocess
import wave

import numpy as np

from make_dialogue import LINES, MEDIA, RATE, ffmpeg, speak

DURATION = 17 * 60
OPENING_AT = [25, 70, 45, 100]          # seconds of cold open before the theme, per episode
OPENING_LEN = 30
ENDING_LEN = 45
ENDING_BEFORE_END = 110                  # the ending theme starts this long before the end


def music(seconds, seed, gain):
    """A song: a chord progression with a melody over it and a beat, all from one seed."""
    rng = np.random.default_rng(seed)
    n = int(seconds * RATE)
    out = np.zeros(n)
    t = 0
    progression = [int(r) for r in rng.integers(0, 12, 4)]
    bar = 0
    while t < n:
        length = int(RATE * 0.5)
        root = 48 + progression[bar % len(progression)]
        chord = [root, root + (4 if bar % 3 else 3), root + 7]
        melody = root + 12 + int(rng.choice([0, 2, 4, 7, 9, 12]))
        k = np.arange(min(length, n - t))
        tt = (t + k) / RATE
        x = np.zeros(len(k))
        for note in chord:
            hz = 440 * 2 ** ((note - 69) / 12)
            x += 0.5 * np.sin(2 * np.pi * hz * tt) + 0.2 * np.sin(4 * np.pi * hz * tt)
        hz = 440 * 2 ** ((melody - 69) / 12)
        x += 0.8 * np.sin(2 * np.pi * hz * tt) * np.exp(-3 * k / RATE)
        kick = np.exp(-40 * k / RATE) * np.sin(2 * np.pi * 60 * k / RATE) * 1.5
        x += kick + rng.standard_normal(len(k)) * 0.15 * np.exp(-60 * k / RATE)
        out[t:t + len(k)] = x
        t += length
        bar += 1
    out = out / (np.sqrt(np.mean(out ** 2)) + 1e-9) * gain
    return out


def add(mix, at_s, clip):
    start = int(at_s * RATE)
    end = min(len(mix), start + len(clip))
    if start < end:
        mix[start:end] += clip[: end - start]


def episode(number, voices, opening, ending):
    rng = np.random.default_rng(100 + number)
    mix = np.zeros(DURATION * RATE)
    open_at = OPENING_AT[number - 1]
    end_at = DURATION - ENDING_BEFORE_END
    busy = [(open_at, open_at + OPENING_LEN), (end_at, end_at + ENDING_LEN)]

    def free(a, b):
        return all(b <= s or a >= e for s, e in busy)

    # Talk throughout, with the odd burst, and a few stretches of other music (a score).
    pos = 2.0
    i = int(rng.integers(0, len(voices)))
    while pos < DURATION - 3:
        clip = voices[i % len(voices)]
        if free(pos, pos + len(clip) / RATE):
            add(mix, pos, clip)
        pos += len(clip) / RATE + float(rng.uniform(0.6, 3.5))
        i += 1
        if rng.random() < 0.08 and free(pos, pos + 2):
            burst = rng.standard_normal(int(1.5 * RATE)) * 0.4 * np.exp(-np.linspace(0, 4, int(1.5 * RATE)))
            add(mix, pos, burst)
            pos += 2
    for k in range(4):
        at = float(rng.uniform(150, DURATION - 200))
        if free(at, at + 25):
            add(mix, at, music(25, 1000 + number * 10 + k, 0.05))

    add(mix, open_at, opening)
    add(mix, end_at, ending)
    mix = np.clip(mix, -1, 1)
    audio = os.path.join(MEDIA, "tts", "theme_ep%d.wav" % number)
    pcm = (np.stack([mix, mix], axis=1) * 32767).astype(np.int16)
    with wave.open(audio, "wb") as w:
        w.setnchannels(2)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(pcm.tobytes())

    font = "C\\:/Windows/Fonts/arial.ttf"
    label = "drawtext=fontfile='%s':fontsize=%d:fontcolor=%s:x=(w-text_w)/2:y=%s:text='%s'%s"
    filters = ",".join([
        label % (font, 28, "white", "40", "EPISODE %d  %%{pts\\:hms}" % number, ""),
        label % (font, 44, "yellow", "(h-text_h)/2", "OPENING THEME", ":enable='between(t,%d,%d)'" % (open_at, open_at + OPENING_LEN)),
        label % (font, 44, "orange", "(h-text_h)/2", "ENDING THEME", ":enable='between(t,%d,%d)'" % (end_at, end_at + ENDING_LEN)),
    ])
    subprocess.run([
        ffmpeg(), "-y", "-loglevel", "error",
        "-f", "lavfi", "-i", "color=c=0x202830:size=640x360:rate=12:duration=%d" % DURATION,
        "-i", audio, "-vf", filters,
        "-c:v", "libx264", "-preset", "veryfast", "-g", "24", "-b:v", "150k",
        "-c:a", "aac", "-b:a", "96k", "-movflags", "+faststart", "-shortest",
        os.path.join(MEDIA, "theme_ep%d.mp4" % number),
    ], check=True)
    # Decoded back through AAC, as the app hears it, for core's ThemesOnRealAudioTest.
    subprocess.run([
        ffmpeg(), "-y", "-loglevel", "error", "-i", os.path.join(MEDIA, "theme_ep%d.mp4" % number),
        "-vn", "-ac", "2", "-ar", str(RATE), os.path.join(MEDIA, "tts", "theme_ep%d.decoded.wav" % number),
    ], check=True)
    return {"intro": [open_at * 1000, (open_at + OPENING_LEN) * 1000],
            "outro": [end_at * 1000, (end_at + ENDING_LEN) * 1000],
            "duration": DURATION * 1000}


def main():
    os.makedirs(os.path.join(MEDIA, "tts"), exist_ok=True)
    voices = speak(LINES, os.path.join(MEDIA, "tts"))
    opening = music(OPENING_LEN, 7, 0.2)     # loud, as the complaint goes
    ending = music(ENDING_LEN, 8, 0.2)
    truth = {str(n): episode(n, voices, opening, ending) for n in range(1, 5)}
    with open(os.path.join(MEDIA, "themes.json"), "w") as fh:
        json.dump(truth, fh, indent=1)
    print("wrote theme_ep1-4.mp4 and themes.json")


if __name__ == "__main__":
    main()
