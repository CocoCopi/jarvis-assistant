import os
import shutil
import subprocess
import sys
import tempfile
import time
import wave
from collections import deque
from typing import Any

SAMPLE_RATE = 16000
BLOCK_MS = 30


def _sd() -> Any:
    try:
        import sounddevice as sd
    except ImportError as exc:
        raise RuntimeError(
            "Voice input needs the 'sounddevice' package and the PortAudio library.\n"
            "  pip install numpy sounddevice\n"
            "  Debian/Ubuntu: sudo apt install libportaudio2"
        ) from exc
    try:
        sd.query_devices(kind="input")
    except Exception:
        try:
            import atexit

            atexit.unregister(sd._exit_handler)
        except Exception:
            pass
    return sd


def _numpy() -> Any:
    try:
        import numpy as np
    except ImportError as exc:
        raise RuntimeError("Voice input needs numpy: pip install numpy") from exc
    return np


def list_devices() -> str:
    sd = _sd()
    try:
        devices = sd.query_devices()
    except Exception as exc:
        return f"Could not query audio devices: {exc}"
    lines = []
    for index, device in enumerate(devices):
        if device.get("max_input_channels", 0) > 0:
            lines.append(
                f"[{index}] {device['name']} "
                f"({device['max_input_channels']} in, default rate {int(device['default_samplerate'])})"
            )
    return "\n".join(lines) or "No input devices found."


class Microphone:
    def __init__(self, device: str | int | None = None, samplerate: int = SAMPLE_RATE) -> None:
        self.samplerate = samplerate
        self.device = int(device) if isinstance(device, str) and device.isdigit() else device

    def record(
        self,
        max_seconds: float = 12.0,
        silence_seconds: float = 0.9,
        min_seconds: float = 0.3,
        onset_multiplier: float = 2.5,
        calibration_seconds: float = 0.35,
    ) -> Any:
        """Record one utterance using simple energy-based voice activity detection."""
        sd = _sd()
        np = _numpy()
        block_size = int(self.samplerate * BLOCK_MS / 1000)

        try:
            stream = sd.InputStream(
                samplerate=self.samplerate,
                channels=1,
                dtype="int16",
                blocksize=block_size,
                device=self.device,
            )
        except Exception as exc:
            raise RuntimeError(f"Could not open microphone: {exc}") from exc

        with stream:
            calibration_blocks = max(1, int(calibration_seconds / (BLOCK_MS / 1000)))
            levels = []
            for _ in range(calibration_blocks):
                frame, overflowed = stream.read(block_size)
                if not overflowed:
                    levels.append(self._rms(frame[:, 0]))
            noise = float(np.median(levels)) if levels else 0.0
            threshold = max(180.0, noise * onset_multiplier)

            pre = deque(maxlen=max(1, int(0.3 / (BLOCK_MS / 1000))))
            recorded: list[Any] = []
            speaking = False
            silent_for = 0.0
            started = time.monotonic()

            while time.monotonic() - started < max_seconds:
                frame, overflowed = stream.read(block_size)
                if overflowed:
                    continue
                mono = frame[:, 0]
                level = self._rms(mono)

                if not speaking:
                    pre.append(mono)
                    if level > threshold:
                        speaking = True
                        recorded = list(pre)
                        silent_for = 0.0
                else:
                    recorded.append(mono)
                    if level > threshold:
                        silent_for = 0.0
                    else:
                        silent_for += BLOCK_MS / 1000
                        if silent_for >= silence_seconds:
                            break

        if not speaking or not recorded:
            return np.zeros(0, dtype=np.int16)
        audio = np.concatenate(recorded)
        if len(audio) / self.samplerate < min_seconds:
            return np.zeros(0, dtype=np.int16)
        return audio

    @staticmethod
    def _rms(frame: Any) -> float:
        np = _numpy()
        samples = frame.astype(np.float32)
        return float(np.sqrt(np.mean(samples * samples) + 1e-9))


def to_wav_bytes(audio: Any, samplerate: int = SAMPLE_RATE) -> bytes:
    """Encode int16 mono samples as a WAV byte string."""
    with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as handle:
        path = handle.name
    try:
        with wave.open(path, "wb") as wav:
            wav.setnchannels(1)
            wav.setsampwidth(2)
            wav.setframerate(samplerate)
            wav.writeframes(audio.astype("int16").tobytes())
        with open(path, "rb") as handle:
            return handle.read()
    finally:
        try:
            os.unlink(path)
        except OSError:
            pass


def play_file(path: str) -> bool:
    """Play an audio file with whatever player is available."""
    if not os.path.exists(path):
        return False

    if path.lower().endswith(".wav"):
        try:
            import sounddevice as sd

            with wave.open(path, "rb") as wav:
                data = wav.readframes(wav.getnframes())
                rate = wav.getframerate()
                channels = wav.getnchannels()
            import numpy as np

            samples = np.frombuffer(data, dtype=np.int16)
            if channels > 1:
                samples = samples.reshape(-1, channels)
            sd.play(samples, rate)
            sd.wait()
            return True
        except Exception:
            pass

    player = _find_player()
    if not player:
        return False
    try:
        subprocess.run(
            player + [path],
            check=True,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            timeout=120,
        )
        return True
    except Exception:
        return False


def _find_player() -> list[str] | None:
    candidates = [
        ["ffplay", "-nodisp", "-autoexit", "-loglevel", "quiet"],
        ["mpv", "--no-video", "--really-quiet"],
        ["afplay"],
        ["mpg123", "-q"],
        ["aplay", "-q"],
        ["play", "-q"],
    ]
    for candidate in candidates:
        if shutil.which(candidate[0]):
            return candidate
    return None


def audio_available() -> tuple[bool, str]:
    try:
        sd = _sd()
        _numpy()
    except RuntimeError as exc:
        return False, str(exc)
    try:
        sd.query_devices(kind="input")
        return True, "Audio input available."
    except Exception as exc:
        return False, f"No usable microphone: {exc}"
