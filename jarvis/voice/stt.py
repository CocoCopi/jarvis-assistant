import json
import os
import tempfile
import urllib.error
import urllib.request
import wave
from typing import Any

from .audio import SAMPLE_RATE, to_wav_bytes

BACKENDS = ("faster-whisper", "whisper", "openai", "google")


def available_backends() -> list[str]:
    found = []
    try:
        import faster_whisper  # noqa: F401

        found.append("faster-whisper")
    except ImportError:
        pass
    try:
        import whisper  # noqa: F401

        found.append("whisper")
    except ImportError:
        pass
    if os.environ.get("OPENAI_API_KEY"):
        found.append("openai")
    try:
        import speech_recognition  # noqa: F401

        found.append("google")
    except ImportError:
        pass
    return found


class Transcriber:
    def __init__(
        self,
        backend: str = "auto",
        model_size: str = "base",
        language: str = "en",
        api_key: str | None = None,
        base_url: str = "https://api.openai.com/v1",
    ) -> None:
        self.language = language
        self.model_size = model_size
        self.api_key = api_key
        self.base_url = base_url.rstrip("/")
        self._model: Any = None
        self.backend = backend
        if backend == "auto":
            found = available_backends()
            self.backend = found[0] if found else "none"
        if self.backend == "none":
            raise RuntimeError(
                "No speech-to-text backend is installed. Choose one:\n"
                "  pip install faster-whisper   (offline, recommended)\n"
                "  pip install openai-whisper   (offline)\n"
                "  pip install SpeechRecognition (online Google)\n"
                "  or set OPENAI_API_KEY and use --stt openai"
            )

    def transcribe(self, audio: Any, samplerate: int = SAMPLE_RATE) -> str:
        if audio is None or len(audio) == 0:
            return ""
        if self.backend == "faster-whisper":
            return self._faster_whisper(audio, samplerate)
        if self.backend == "whisper":
            return self._whisper(audio, samplerate)
        if self.backend == "openai":
            return self._openai(audio, samplerate)
        if self.backend == "google":
            return self._google(audio, samplerate)
        raise RuntimeError(f"Unknown speech-to-text backend: {self.backend}")

    def _faster_whisper(self, audio: Any, samplerate: int) -> str:
        import numpy as np
        from faster_whisper import WhisperModel

        if self._model is None:
            self._model = WhisperModel(self.model_size, device="cpu", compute_type="int8")
        samples = audio.astype(np.float32) / 32768.0
        segments, _ = self._model.transcribe(
            samples,
            language=self.language,
            beam_size=1,
            vad_filter=True,
        )
        return " ".join(segment.text.strip() for segment in segments).strip()

    def _whisper(self, audio: Any, samplerate: int) -> str:
        import numpy as np
        import whisper

        if self._model is None:
            self._model = whisper.load_model(self.model_size)
        samples = audio.astype(np.float32) / 32768.0
        result = self._model.transcribe(samples, language=self.language, fp16=False)
        return str(result.get("text", "")).strip()

    def _openai(self, audio: Any, samplerate: int) -> str:
        import uuid

        key = self.api_key or os.environ.get("OPENAI_API_KEY")
        if not key:
            raise RuntimeError("openai speech-to-text requires OPENAI_API_KEY")
        wav_bytes = to_wav_bytes(audio, samplerate)
        boundary = f"----jarvis{uuid.uuid4().hex}"
        parts: list[bytes] = []
        parts.append(
            f"--{boundary}\r\n"
            'Content-Disposition: form-data; name="file"; filename="audio.wav"\r\n'
            "Content-Type: audio/wav\r\n\r\n".encode()
        )
        parts.append(wav_bytes)
        parts.append(
            f"\r\n--{boundary}\r\n"
            'Content-Disposition: form-data; name="model"\r\n\r\n'
            "whisper-1\r\n".encode()
        )
        if self.language:
            parts.append(
                f"--{boundary}\r\n"
                'Content-Disposition: form-data; name="language"\r\n\r\n'
                f"{self.language}\r\n".encode()
            )
        parts.append(f"--{boundary}--\r\n".encode())
        body = b"".join(parts)
        request = urllib.request.Request(
            f"{self.base_url}/audio/transcriptions",
            data=body,
            method="POST",
            headers={
                "authorization": f"Bearer {key}",
                "content-type": f"multipart/form-data; boundary={boundary}",
            },
        )
        try:
            with urllib.request.urlopen(request, timeout=60) as response:
                payload = json.loads(response.read().decode())
        except urllib.error.HTTPError as exc:
            detail = exc.read().decode(errors="replace")
            raise RuntimeError(f"OpenAI transcription failed ({exc.code}): {detail}") from exc
        except urllib.error.URLError as exc:
            raise RuntimeError(f"OpenAI transcription failed: {exc.reason}") from exc
        return str(payload.get("text", "")).strip()

    def _google(self, audio: Any, samplerate: int) -> str:
        import speech_recognition as sr

        recognizer = sr.Recognizer()
        try:
            wav_bytes = to_wav_bytes(audio, samplerate)
            with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as handle:
                handle.write(wav_bytes)
                path = handle.name
            try:
                with sr.AudioFile(path) as source:
                    data = recognizer.record(source)
            finally:
                try:
                    os.unlink(path)
                except OSError:
                    pass
            return recognizer.recognize_google(data, language=self.language).strip()
        except sr.UnknownValueError:
            return ""
        except Exception as exc:
            raise RuntimeError(f"Google speech recognition failed: {exc}") from exc
