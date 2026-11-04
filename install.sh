#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

if command -v apt-get >/dev/null 2>&1 && [ "$(id -u)" = "0" ]; then
  echo "Installing system audio/desktop/phone libraries..."
  apt-get update -qq || true
  apt-get install -y -qq python3-venv libportaudio2 ffmpeg adb || true
fi

echo "Creating virtual environment..."
python3 -m venv .venv
.venv/bin/python -m pip install --upgrade pip --quiet

echo "Installing voice requirements (prebuilt wheels only)..."
if ! .venv/bin/pip install --only-binary=:all: --progress-bar off -r requirements-voice.txt; then
  echo "  faster-whisper wheels unavailable; falling back to online STT + TTS"
  .venv/bin/pip install --only-binary=:all: --progress-bar off \
    numpy sounddevice edge-tts SpeechRecognition pyttsx3
fi

echo "Installing desktop helpers..."
.venv/bin/pip install --only-binary=:all: --progress-bar off -q \
  pillow pyperclip setuptools wheel six || true
.venv/bin/pip install --no-deps --no-build-isolation --progress-bar off -q pyautogui \
  && .venv/bin/pip install --no-deps --no-build-isolation --progress-bar off -q \
       pymsgbox pytweening pyscreeze pygetwindow pyrect mouseinfo python-xlib \
  || echo "  (pyautogui unavailable; screenshots and clipboard still work)"

echo "Installing Jarvis..."
.venv/bin/pip install -e . --no-deps --quiet

chmod +x jarvis.sh
echo
echo "Done. Try:"
echo "  ./jarvis.sh demo"
echo "  ./jarvis.sh --provider nvidia \"what time is it?\""
echo "  ./jarvis.sh --provider nvidia voice"
