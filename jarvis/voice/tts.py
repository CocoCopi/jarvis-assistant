import asyncio
import os
import shutil
import subprocess
import sys
import tempfile
from typing import Callable

from .audio import play_file

BACKENDS = ("edge", "piper", "pyttsx3", "say", "espeak")


def available_backends() -> list[str]:
    found = []
    try:
        import edge_tts  # noqa: F401

        found.append("edge")
    except ImportError:
        pass
    if shutil.which("piper") and os.environ.get("JARVIS_PIPER_MODEL"):
        found.append("piper")
    try:
        import pyttsx3  # noqa: F401

        found.append("pyttsx3")
    except ImportError:
        pass
    if sys.platform == "darwin" and shutil.which("say"):
        found.append("say")
    for name in ("espeak-ng", "espeak"):
        if shutil.which(name):
            found.append("espeak")
            break
    return found


class Speaker:
    def __init__(
        self,
        backend: str = "auto",
        voice: str = "en-GB-RyanNeural",
        fallback: Callable[[str], None] | None = None,
    ) -> None:
        self.voice = voice
        self.fallback = fallback or (lambda text: print(f"Jarvis: {text}", flush=True))
        self._engine = None
        if backend == "auto":
            found = available_backends()
            self.backends = found or ["none"]
        else:
            self.backends = [backend]

    def say(self, text: str) -> None:
        text = text.strip()
        if not text:
            return
        for backend in self.backends:
            try:
                if backend == "edge":
                    self._edge(text)
                elif backend == "piper":
                    self._piper(text)
                elif backend == "pyttsx3":
                    self._pyttsx3(text)
                elif backend == "say":
                    self._say(text)
                elif backend == "espeak":
                    self._espeak(text)
                elif backend == "none":
                    continue
                else:
                    continue
                return
            except Exception:
                continue
        self.fallback(text)

    def stop(self) -> None:
        pass

    def _edge(self, text: str) -> None:
        import edge_tts

        handle = tempfile.NamedTemporaryFile(suffix=".mp3", delete=False)
        path = handle.name
        handle.close()
        try:
            asyncio.run(edge_tts.Communicate(text, self.voice).save(path))
            if not play_file(path):
                raise RuntimeError("no audio player available for edge-tts output")
        finally:
            try:
                os.unlink(path)
            except OSError:
                pass

    def _piper(self, text: str) -> None:
        model = os.environ.get("JARVIS_PIPER_MODEL")
        if not model:
            raise RuntimeError("JARVIS_PIPER_MODEL is not set")
        handle = tempfile.NamedTemporaryFile(suffix=".wav", delete=False)
        path = handle.name
        handle.close()
        try:
            subprocess.run(
                ["piper", "--model", model, "--output_file", path],
                input=text.encode(),
                check=True,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                timeout=60,
            )
            if not play_file(path):
                raise RuntimeError("no audio player available for piper output")
        finally:
            try:
                os.unlink(path)
            except OSError:
                pass

    def _pyttsx3(self, text: str) -> None:
        import pyttsx3

        if self._engine is None:
            self._engine = pyttsx3.init()
            self._engine.setProperty("rate", 172)
        self._engine.say(text)
        self._engine.runAndWait()

    def _say(self, text: str) -> None:
        subprocess.run(["say", "-v", "Daniel", text], check=True, timeout=60)

    def _espeak(self, text: str) -> None:
        binary = shutil.which("espeak-ng") or shutil.which("espeak")
        if not binary:
            raise RuntimeError("espeak not installed")
        subprocess.run(
            [binary, "-v", "en-gb", "-s", "165", text],
            check=True,
            timeout=60,
        )
