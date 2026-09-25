"""
Builds the caption test films for the mock server (`server.py --captions`).

A four-minute film whose soundtrack is what makes captions hard: spoken lines (Windows' built-in
speech synthesiser, so there is no licensing question), with loud noise bursts ("explosions") and
a music bed between them. Produces, in media/:

  dialogue.mkv        the film, no subtitle track: the app has to find captions online
  dialogue_subs.mkv   the same film with an English subtitle track in the file
  dual.mkv            the same film with two audio tracks, "Japanese" (a tone) marked default
                      and the English dialogue second
  dialogue.srt        the true timing of every line
  dialogue_early.srt  the same captions 2.7 s early, as a subtitle made for another release is;
                      the mock's subtitle service hands this one out, so automatic sync has
                      something to correct

The true line is also burned into the top of the picture, so a screenshot shows at a glance
whether the app's captions (at the bottom) are in step with it.

Run: python make_dialogue.py   (Windows, with ffmpeg on PATH or in D:/tools/ffmpeg/bin)
"""
import os
import random
import shutil
import subprocess
import wave

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
MEDIA = os.path.join(HERE, "media")
RATE = 48_000
DURATION = 240
EARLY_MS = 2_700

LINES = [
    "We should have been out of here an hour ago.",
    "The road north is closed. We take the river.",
    "Nobody told me the bridge was gone.",
    "Keep your voice down. They can hear everything.",
    "How long until the power comes back?",
    "Ten minutes, maybe less. Don't count on it.",
    "I left the keys on the table by the door.",
    "Then we walk. It's not that far.",
    "It's six miles in the dark, in the rain.",
    "You have a better idea, I'm listening.",
    "Did you hear that? Over by the fence.",
    "It's the wind. It's always the wind.",
    "Tell me again why we trusted him.",
    "Because he was the only one who answered.",
    "The radio still works if you hold the wire.",
    "Say something. Anyone out there, please respond.",
    "This is station four. We read you. Go ahead.",
    "We need a way across before morning.",
    "There's a boat at the old mill, if it still floats.",
    "Everything floats if you're brave enough.",
    "That is not how boats work.",
    "Hold the light steady. I can't see the rope.",
    "Got it. Pull. Pull harder.",
    "We're moving. Don't look down.",
    "I wasn't going to look down until you said that.",
    "Almost there. Grab my hand.",
    "We made it. We actually made it.",
    "Don't celebrate yet. Look at the sky.",
    "That's not a storm. That's a signal fire.",
    "Then someone knows we're coming.",
    "Or someone wants us to think so.",
    "Either way, we go and find out.",
    "Stay close. Stay quiet. And stay alive.",
    "That last one's the hard part.",
    "It always is. Let's go.",
]


def ffmpeg():
    return shutil.which("ffmpeg") or r"D:\tools\ffmpeg\bin\ffmpeg.exe"


def speak(lines, folder):
    """One WAV per line from Windows' speech synthesiser, alternating two voices."""
    script = os.path.join(folder, "speak.ps1")
    with open(script, "w", encoding="utf-8") as fh:
        fh.write("Add-Type -AssemblyName System.Speech\n")
        fh.write("$s = New-Object System.Speech.Synthesis.SpeechSynthesizer\n")
        fh.write("$voices = @($s.GetInstalledVoices() | ForEach-Object { $_.VoiceInfo.Name })\n")
        for i, line in enumerate(lines):
            path = os.path.join(folder, "line_%02d.wav" % i)
            fh.write("$s.SelectVoice($voices[%d %% $voices.Count])\n" % i)
            fh.write("$s.SetOutputToWaveFile('%s')\n" % path)
            fh.write("$s.Speak(\"%s\")\n" % line.replace('"', "'"))
        fh.write("$s.SetOutputToNull()\n")
    subprocess.run(["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", script], check=True)
    out = []
    for i in range(len(lines)):
        with wave.open(os.path.join(folder, "line_%02d.wav" % i)) as w:
            data = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype(np.float32) / 32768
            if w.getnchannels() == 2:
                data = data.reshape(-1, 2).mean(axis=1)
            src_rate = w.getframerate()
        # Resample to 48 kHz by linear interpolation; plenty for a test soundtrack.
        n = int(len(data) * RATE / src_rate)
        data = np.interp(np.linspace(0, len(data) - 1, n), np.arange(len(data)), data)
        # Trim the synthesiser's leading and trailing silence, so cue times are speech times.
        loud = np.nonzero(np.abs(data) > 0.02)[0]
        data = data[loud[0]:loud[-1]] if len(loud) else data
        out.append(data / (np.sqrt(np.mean(data ** 2)) + 1e-9) * 0.1)  # about -20 dBFS RMS
    return out


def srt_time(ms):
    ms = max(0, int(ms))
    return "%02d:%02d:%02d,%03d" % (ms // 3_600_000, ms // 60_000 % 60, ms // 1000 % 60, ms % 1000)


def write_srt(path, cues, shift_ms=0):
    with open(path, "w", encoding="utf-8", newline="\n") as fh:
        for i, (start, end, text) in enumerate(cues):
            fh.write("%d\n%s --> %s\n%s\n\n" % (i + 1, srt_time(start + shift_ms), srt_time(end + shift_ms), text))


def main():
    os.makedirs(MEDIA, exist_ok=True)
    work = os.path.join(MEDIA, "tts")
    os.makedirs(work, exist_ok=True)
    random.seed(4)
    voices = speak(LINES, work)

    total = DURATION * RATE
    t = np.arange(total) / RATE
    # A quiet music bed: a slow chord, always there.
    mix = 0.012 * (np.sin(2 * np.pi * 110 * t) + np.sin(2 * np.pi * 138.6 * t) + np.sin(2 * np.pi * 164.8 * t))
    mix = np.stack([mix, mix], axis=1)

    cues = []
    pos = 4.0
    for i, clip in enumerate(voices):
        start = int(pos * RATE)
        end = min(total, start + len(clip))
        mix[start:end, 0] += clip[: end - start]
        mix[start:end, 1] += clip[: end - start]
        cues.append((start * 1000 // RATE, end * 1000 // RATE, LINES[i]))
        gap = random.uniform(0.8, 4.5)
        if i % 3 == 2:
            # An explosion: a loud noise burst in the gap, much louder than the speech.
            burst_start = end + int(0.4 * RATE)
            length = int(random.uniform(1.2, 2.2) * RATE)
            if burst_start + length < total:
                envelope = np.exp(-np.linspace(0, 4, length))
                noise = np.random.default_rng(i).standard_normal((length, 2)) * 0.45 * envelope[:, None]
                mix[burst_start:burst_start + length] += noise
            gap += length / RATE + 0.6
        pos = end / RATE + gap
        if pos > DURATION - 6:
            break
    mix = np.clip(mix, -1, 1)
    pcm = (mix * 32767).astype(np.int16)
    audio = os.path.join(work, "dialogue.wav")
    with wave.open(audio, "wb") as w:
        w.setnchannels(2)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(pcm.tobytes())

    srt = os.path.join(MEDIA, "dialogue.srt")
    write_srt(srt, cues)
    write_srt(os.path.join(MEDIA, "dialogue_early.srt"), cues, -EARLY_MS)

    # The true line, burned into the top of the picture.
    srt_filter = srt.replace("\\", "/").replace(":", "\\:")
    video = [
        ffmpeg(), "-y", "-loglevel", "error",
        "-f", "lavfi", "-i", "testsrc2=size=1280x720:rate=24000/1001:duration=%d" % DURATION,
        "-i", audio,
        "-vf", "subtitles='%s':force_style='Alignment=8,Fontsize=20,PrimaryColour=&H0000FFFF'" % srt_filter,
        "-c:v", "libx264", "-preset", "veryfast", "-g", "48", "-b:v", "1200k",
        "-c:a", "aac", "-b:a", "128k",
    ]
    subprocess.run(video + [os.path.join(MEDIA, "dialogue.mkv")], check=True)
    subprocess.run([
        ffmpeg(), "-y", "-loglevel", "error",
        "-i", os.path.join(MEDIA, "dialogue.mkv"), "-i", srt,
        "-map", "0", "-map", "1", "-c", "copy", "-c:s", "srt",
        "-metadata:s:s:0", "language=eng",
        os.path.join(MEDIA, "dialogue_subs.mkv"),
    ], check=True)
    # Dual audio, as an anime release has it: a stand-in "Japanese" track (a tone) marked as the
    # default, and the English dialogue second. `server.py --dual-audio` serves it as every film.
    subprocess.run([
        ffmpeg(), "-y", "-loglevel", "error",
        "-i", os.path.join(MEDIA, "dialogue.mkv"),
        "-f", "lavfi", "-i", "sine=frequency=330:duration=%d:sample_rate=%d" % (DURATION, RATE),
        "-map", "0:v", "-map", "1:a", "-map", "0:a", "-c:v", "copy", "-c:a", "aac", "-b:a", "96k", "-ac", "2",
        "-metadata:s:a:0", "language=jpn", "-metadata:s:a:1", "language=eng",
        "-disposition:a:0", "default", "-disposition:a:1", "0",
        os.path.join(MEDIA, "dual.mkv"),
    ], check=True)
    print("wrote %d lines; dialogue.mkv, dialogue_subs.mkv, dual.mkv, dialogue.srt, dialogue_early.srt" % len(cues))


if __name__ == "__main__":
    main()
