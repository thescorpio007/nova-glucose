# Generates spoken alert proposals with Kokoro (Apache-2.0 voices). Run in CI.
import subprocess, soundfile as sf
from kokoro_onnx import Kokoro
k = Kokoro("kokoro-v1.0.onnx", "voices-v1.0.bin")
P = [
 ("01-warm-female",      "af_heart",   1.0, False, "Please connect your sensor."),
 ("02-friendly-female",  "af_bella",   1.0, False, "Hey! Your sensor is not connected. Please connect it now."),
 ("03-british-female",   "bf_emma",    0.95, False, "Your glucose sensor has lost its connection. Please reconnect your sensor."),
 ("04-nova-assistant",   "af_sky",     1.0, False, "Nova here. I can't read your sensor. Please connect your sensor."),
 ("05-clear-male",       "am_michael", 0.95, False, "Attention. Sensor signal lost. Please connect your sensor."),
 ("06-gentle-british",   "bf_isabella",0.92, False, "Hi there. Please check your sensor. It is not connected."),
 ("07-soft-calm",        "af_nicole",  0.95, False, "Please connect your sensor, so we can keep you safe."),
 ("08-british-male",     "bm_george",  0.95, False, "Sensor disconnected. Open the Libre app and connect your sensor."),
 ("09-chime-then-voice", "af_heart",   1.0, True,  "Please connect your sensor."),
 ("10-urgent-repeat",    "af_bella",   1.05, True, "Please connect your sensor. Please connect your sensor now."),
]
for name, voice, speed, chime, text in P:
    a, sr = k.create(text, voice=voice, speed=speed, lang="en-us" if voice[0] == "a" else "en-gb")
    sf.write(f"{name}.wav", a, sr)
    src = f"{name}.wav"
    if chime:
        subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-f", "lavfi", "-i", "sine=frequency=880:duration=0.18", "-f", "lavfi", "-i", "anullsrc=r=24000:cl=mono:d=0.08",
            "-f", "lavfi", "-i", "sine=frequency=1320:duration=0.25", "-f", "lavfi", "-i", "anullsrc=r=24000:cl=mono:d=0.35", "-i", src,
            "-filter_complex", "[0]aresample=24000,volume=0.5[a];[2]aresample=24000,volume=0.5[c];[4]aresample=24000[v];[a][1][c][3][v]concat=n=5:v=0:a=1[o]", "-map", "[o]", f"c-{name}.wav"], check=True)
        src = f"c-{name}.wav"
    subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", src, "-af", "loudnorm=I=-14:TP=-1", "-ac", "1", "-b:a", "96k", f"out/{name}.mp3"], check=True)
    print(name, "ok")
