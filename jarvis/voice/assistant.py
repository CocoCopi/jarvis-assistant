import re
from typing import Any

from ..agent import Agent
from ..policy import needs_command_approval
from .audio import Microphone
from .stt import Transcriber
from .tts import Speaker

EXIT_PHRASES = (
    "goodbye",
    "good bye",
    "shut down",
    "shutdown",
    "power down",
    "that's all",
    "thats all",
    "stop listening",
    "go to sleep",
)

WAKE_PREFIXES = ("hey", "ok", "okay", "oi")


class VoiceAssistant:
    def __init__(
        self,
        agent: Agent,
        transcriber: Transcriber,
        speaker: Speaker,
        mic: Microphone | None = None,
        wake_word: str = "jarvis",
    ) -> None:
        self.agent = agent
        self.transcriber = transcriber
        self.speaker = speaker
        self.mic = mic or Microphone()
        self.wake_word = wake_word.strip().lower()
        self._wake_pattern = re.compile(
            r"\b(?:(?:hey|ok|okay)\s+)?"
            + re.escape(self.wake_word)
            + r"\b[,.!?]?\s*",
            re.IGNORECASE,
        )

    def run(self) -> None:
        self.speaker.say("Jarvis online. At your service, sir.")
        try:
            while True:
                command = self._next_command()
                if not command:
                    continue
                print(f"You: {command}", flush=True)
                if self._is_exit(command):
                    self.speaker.say("Very good, sir.")
                    return
                try:
                    response = self.agent.run(command)
                except KeyboardInterrupt:
                    raise
                except Exception as exc:
                    response = f"I ran into a problem: {exc}"
                if response:
                    self.speaker.say(response)
        except KeyboardInterrupt:
            print()
            self.speaker.say("Shutting down.")
        finally:
            self.speaker.stop()

    def _next_command(self) -> str:
        if not self.wake_word:
            return self._listen()

        while True:
            print("Listening for wake word...", flush=True)
            audio = self.mic.record(max_seconds=6.0, silence_seconds=0.7)
            if len(audio) == 0:
                continue
            try:
                text = self.transcriber.transcribe(audio)
            except Exception as exc:
                print(f"[speech error] {exc}", flush=True)
                continue
            if not text:
                continue
            match = self._wake_pattern.search(text)
            if not match:
                continue
            remainder = self._wake_pattern.sub("", text, count=1).strip()
            if remainder:
                return remainder
            self.speaker.say("Yes, sir?")
            return self._listen()

    def _listen(self) -> str:
        print("Listening...", flush=True)
        audio = self.mic.record(max_seconds=15.0, silence_seconds=0.9)
        if len(audio) == 0:
            return ""
        try:
            text = self.transcriber.transcribe(audio).strip()
        except Exception as exc:
            print(f"[speech error] {exc}", flush=True)
            return ""
        return text

    def confirm(self, name: str, arguments: dict[str, Any]) -> bool:
        if name == "run_command" and not needs_command_approval(
            str(arguments.get("command", ""))
        ):
            return True
        detail = (
            arguments.get("command")
            or arguments.get("args")
            or arguments.get("code")
            or arguments.get("target")
            or arguments.get("url")
            or arguments.get("path")
            or arguments.get("name")
            or ""
        )
        detail = " ".join(str(detail).split())
        if len(detail) > 200:
            detail = detail[:200] + "..."
        spoken = f"Sir, I need permission to use {name.replace('_', ' ')}."
        if detail:
            spoken += f" {detail}."
        self.speaker.say(spoken + " Shall I proceed?")
        answer = self._listen().lower()
        print(f"Confirmation: {answer!r}", flush=True)
        return any(
            word in answer
            for word in ("yes", "yeah", "yep", "proceed", "go ahead", "do it", "affirmative", "sure")
        )

    @staticmethod
    def _is_exit(command: str) -> bool:
        lowered = command.lower().strip(" .!?")
        return any(phrase in lowered for phrase in EXIT_PHRASES)
