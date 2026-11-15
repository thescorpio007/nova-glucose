# Nova Voice "Please connect your sensor" (proposal 09 style): chime + voice, repeated to ~15 s. en/fr: Kokoro (Apache-2.0), ar: Piper ar_JO-kareem.
import subprocess, soundfile as sf, wave
from kokoro_onnx import Kokoro
k = Kokoro("kokoro-v1.0.onnx", "voices-v1.0.bin")
def chime_plus(src, out):
    subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-f", "lavfi", "-i", "sine=frequency=880:duration=0.18", "-f", "lavfi", "-i", "anullsrc=r=24000:cl=mono:d=0.08",
        "-f", "lavfi", "-i", "sine=frequency=1320:duration=0.25", "-f", "lavfi", "-i", "anullsrc=r=24000:cl=mono:d=0.35", "-i", src, "-f", "lavfi", "-i", "anullsrc=r=24000:cl=mono:d=0.6",
        "-filter_complex", "[0]aresample=24000,volume=0.5[a];[2]aresample=24000,volume=0.5[c];[4]aresample=24000,aformat=channel_layouts=mono[v];[a][1][c][3][v][5]concat=n=6:v=0:a=1[o]", "-map", "[o]", out], check=True)
def finish(lang, voicewav):
    chime_plus(voicewav, f"one-{lang}.wav")
    d = float(subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", f"one-{lang}.wav"], capture_output=True, text=True).stdout)
    n = max(1, round(15.0 / d + 0.49))
    subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-stream_loop", str(n - 1), "-i", f"one-{lang}.wav", "-af", "loudnorm=I=-14:TP=-1", "-ac", "1", "-b:a", "96k", f"out/nova_voice_{lang}.mp3"], check=True)
    subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", f"one-{lang}.wav", "-af", "loudnorm=I=-14:TP=-1", "-ac", "1", "-b:a", "96k", f"out/sample_{lang}.mp3"], check=True)
    print(lang, n, "x", d)
a, sr = k.create("Please connect your sensor.", voice="af_heart", speed=1.0, lang="en-us"); sf.write("en.wav", a, sr); finish("en", "en.wav")
a, sr = k.create("Veuillez connecter votre capteur.", voice="ff_siwis", speed=1.0, lang="fr-fr"); sf.write("fr.wav", a, sr); finish("fr", "fr.wav")
subprocess.run("echo 'الرَّجَاءُ تَوْصِيلُ المُسْتَشْعِرِ.' | piper --model ar_JO-kareem-medium.onnx --output_file ar.wav", shell=True, check=True); finish("ar", "ar.wav")
