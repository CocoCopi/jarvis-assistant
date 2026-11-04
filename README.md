# JARVIS

A voice-first personal assistant in the spirit of Tony Stark's JARVIS — and a
general-purpose action agent for your machine. Wake it with **"Jarvis"**, speak
or type a request, and it actually does it: runs shell commands and Python,
controls the desktop, calls web APIs, manages files, sets timers, remembers
facts, and **writes new skills for itself**.

Python is deliberate here: it has the deepest bindings to the OS, desktop,
network, and other languages, and it can generate and import new tools at
runtime. That is what makes "do anything" practical.

## Install

```bash
cd /root/Jarvis
./install.sh
./jarvis.sh doctor
```

`install.sh` creates a `.venv`, installs the system audio libraries and the
voice/desktop Python packages, and installs Jarvis itself. It is safe to re-run.
Debian and Ubuntu block system-wide pip (PEP 668), so the `./jarvis.sh` launcher
uses the virtual environment automatically. Text mode alone needs only Python
3.10+ and an API key; the venv is for voice and desktop extras.

```bash
./jarvis.sh demo                  # full self-test, no key, no mic
```

The demo exercises the real pipeline: reads the clock, executes Python, creates
a new `roll_dice` skill on the fly, calls it, and checks system status.

## Quick start

Text mode needs only an API key:

```bash
export ANTHROPIC_API_KEY=sk-ant-...
./jarvis.sh "check the time, then find my largest files"
./jarvis.sh                       # interactive REPL
```

NVIDIA NIM:

```bash
export NVIDIA_API_KEY=nvapi-...
./jarvis.sh --provider nvidia "what time is it?"
./jarvis.sh --provider nvidia     # REPL
```

Everything (voice + desktop + web + phone control) is installed by `install.sh`:

```bash
./jarvis.sh --provider nvidia voice
```


## What it can do

| Area | Tools | Approval |
| --- | --- | --- |
| System | `get_time`, `get_weather`, `system_status`, `set_volume`, `notify` | no |
| Files | `read_file`, `write_file`, `list_directory` | no |
| Shell / code | `run_command`, `run_python` | destructive commands and Python |
| Web | `web_search`, `fetch_url`, `http_request` | no |
| Desktop | `screenshot`, `mouse_control`, `keyboard_control`, `clipboard`, `power` | power |
| Phone | `phone_control`, `phone_shell`, `phone_adb`, `phone_install`, `phone_uninstall` | shell, adb, install, uninstall |
| Memory | `remember`, `recall`, `forget`, `set_timer` | no |
| Apps | `open_app` | no |
| Self-extension | `create_skill`, `delete_skill`, `list_skills` | create |

`run_python` and `run_command` mean the assistant can do anything your user
account can do. The desktop tools mean it can operate GUI apps like a person.
The phone tools mean it can do anything ADB can do on your Android device. The
web tools mean it can reach any API or page.

### Phone control (Android, over ADB)

Jarvis drives a real Android phone: taps, swipes, typing, keys, launching apps
and URLs, reading the screen, screenshots, files, installed apps, installs,
uninstalls, and an unrestricted `adb shell`. On a rooted phone, `phone_shell`
can run `su -c ...`; `phone_adb` can run `root`, `remount`, `reboot bootloader`,
`forward`, `backup`, and so on.

Setup:

```bash
sudo apt install adb             # Debian/Ubuntu
# On the phone: Settings > About > tap Build number 7x,
# then Developer options > USB debugging, plug in, accept the RSA prompt.
./jarvis.sh doctor               # should show Phone (adb): yes - 1 device(s)
adb devices                      # sanity check
```

Wireless debugging (Android 11+):

```bash
adb pair 192.168.1.50:37123 123456
adb connect 192.168.1.50:5555
```

Examples:

```bash
./jarvis.sh "open YouTube on my phone, search for lofi, and play the first result"
./jarvis.sh "read my phone screen, then tap the Settings icon"
./jarvis.sh "take a phone screenshot and tell me what is on screen"
./jarvis.sh "install /root/app.apk on my phone"
./jarvis.sh "run adb root on my phone"
```

`phone_control` (taps, typing, screenshots, file transfer) runs without prompts.
`phone_shell`, `phone_adb`, installs and uninstalls ask once because they are
unrestricted.

### Native Android app

The `android/` directory is a Chaquopy app that runs the same Python core on
the phone itself. It is not the ADB client: it has native access to
accessibility, notifications, shell, root, SMS, calls, intents, files,
installers, device admin and arbitrary Python. The app is `com.jarvis.assistant`
and needs Android 11 or newer.

Builds are done by GitHub Actions because this project does not require a local
Android SDK. The repository must be public for the app to fetch releases without
a GitHub token:

1. Push this repository to GitHub.
2. On every push to `main`, the workflow publishes a source-only prerelease
   containing `jarvis-src.zip` and its SHA-256. The installed app checks the
   latest releases and applies that code without reinstalling.
3. For a signed APK, create a keystore once and add these Actions secrets:

```bash
JARVIS_KEYSTORE_PASSWORD='choose-a-strong-password' \
  ./scripts/create_keystore.sh release.keystore
base64 -w0 release.keystore        # paste into ANDROID_KEYSTORE_BASE64
```

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS` (the alias defaults to `jarvis`)
- `ANDROID_KEY_PASSWORD` (optional; defaults to the keystore password)

Then push a tag such as `v0.5.0`. The workflow builds and signs the APK, packages
the Python source, and attaches both to that GitHub release. Install the APK
once. Future code updates are downloaded by the app; APK updates use the same
signature and keep app data. A different signing key always requires one
uninstall, so set the secrets before installing.

After installing, open **Access** and grant the permissions you want:
accessibility (screen reading, taps, screenshots), notification listener,
battery exemption, all-files access, overlay, install-unknown-apps, SMS/calls/
contacts/location, and device admin. For root shell, root the phone and grant
`su`; for device-owner policy control, use the ADB command shown in the app.

Build requirements for local builds: JDK 17, Python 3.14 (must match Chaquopy),
Android SDK platform 36 and build-tools 36.0.0. The workflow uses Gradle 8.14.3.

### Skills: it can extend itself

`create_skill` writes a permanent Python file to `~/.jarvis/skills/`. New tools
become callable in the same conversation, then survive restarts. Ask:

> "Jarvis, create a skill that converts currencies using an API, then use it."

A skill file is ordinary Python:

```python
# ~/.jarvis/skills/hello.py
def hello(name):
    return f"Hello, {name}."


TOOLS = [
    {
        "name": "hello",
        "description": "Greet someone by name.",
        "input_schema": {
            "type": "object",
            "properties": {"name": {"type": "string"}},
            "required": ["name"],
        },
        "func": hello,
        "requires_approval": False,
    }
]
```

Set `JARVIS_SKILLS_DIR` to change the location. Skills are validated when
written, and broken files are removed instead of being saved.

## Voice mode

```bash
./jarvis.sh voice
```

Say **"Jarvis, what's the weather in London?"** or just **"Jarvis"** and wait
for "Yes, sir?".

| Layer | Backends | Notes |
| --- | --- | --- |
| Speech to text | `faster-whisper`, `whisper`, `openai`, `google` | Offline by default |
| Text to speech | `edge`, `piper`, `pyttsx3`, `say`, `espeak` | `edge` gives the British butler voice |
| Wake word | transcript matching | No extra wake-word model |
| Audio | `sounddevice`/PortAudio, or ffplay/mpv/afplay/aplay | |

Diagnostics:

```bash
./jarvis.sh doctor
```

Useful voice flags:

```bash
./jarvis.sh voice --no-wake                  # no wake word
./jarvis.sh voice --input-device 2           # choose a microphone
./jarvis.sh voice --tts pyttsx3              # offline voice
./jarvis.sh voice --stt openai --language en
./jarvis.sh voice --voice-name en-GB-SoniaNeural
./jarvis.sh voice --unsafe                   # no spoken confirmations
```

## Providers

| Provider | Env vars | Default model |
| --- | --- | --- |
| `anthropic` | `ANTHROPIC_API_KEY` | `claude-sonnet-4-5` |
| `openai` | `OPENAI_API_KEY`, optional `JARVIS_BASE_URL` | `gpt-4o-mini` |
| `nvidia` / `nim` | `NVIDIA_API_KEY` or `NGC_API_KEY` | `nvidia/nemotron-3.5-lightning-30b-a3b` |
| `ollama` | none, optional `JARVIS_BASE_URL` | `llama3.1` |

Any OpenAI-compatible endpoint works:

```bash
./jarvis.sh --provider openai \
  --base-url http://localhost:8080/v1 --api-key local --model my-model \
  "summarise the files in this directory and write a report"
```

### NVIDIA NIM

NIM is OpenAI-compatible, so it is a first-class provider here.

```bash
export NVIDIA_API_KEY=nvapi-...             # https://build.nvidia.com
./jarvis.sh --provider nvidia "check system status and read the time"
./jarvis.sh --provider nvidia models  # list ids your key can call
./jarvis.sh --provider nvidia --model nvidia/nemotron-3.5-lightning-30b-a3b \
  "find the largest files in this project"
```

`nim` is an accepted alias for `nvidia`, and `NGC_API_KEY` works if you use NGC
credentials. Model access varies by account: `models` shows the catalogue, but
a `404 Function ... not found for account` or `410 ... end of life` means that
particular id is not callable for your key — pick another. `JARVIS_MODEL` sets
the default so you can drop `--model`.

Self-hosted NIM works too, no cloud key required:

```bash
./jarvis.sh --provider nvidia \
  --base-url http://localhost:8000/v1 \
  --model <served-model-id> \
  "status report, please"
```

Offline:

```bash
ollama serve && ollama pull llama3.1
./jarvis.sh --provider ollama "hello"
```

## Autonomy and approvals

Each request is a multi-step loop: the model plans, calls a tool, reads the
result, and continues until it is done. There is no step limit by default;
`--max-steps N` adds one, and `--max-steps 0` removes it again. Tool results are
always fed back before it claims success.

Approvals are reserved for the most dangerous actions:

- `run_command` only prompts when the command matches destructive patterns
  (`rm -rf`, `dd of=`, `mkfs`, `sudo`, `shutdown`, `reboot`, piping downloads
  into a shell, dropping databases, force-pushing, and similar).
- `run_python`, `create_skill`, `power`, `phone_shell`, `phone_adb`,
  `phone_install` and `phone_uninstall` always prompt.
- Everything else (files, web, desktop mouse/keyboard, clipboard, phone taps,
  typing, screenshots and file transfer) runs immediately.

- Text mode: `Allow 'run_command' (...)? [y/N]`
- Voice mode: "Sir, I need permission to use run command. Shall I proceed?"

`--unsafe` (or `JARVIS_UNSAFE=1`) removes every confirmation, including the
phone root shell. Use it only when you trust the model and the prompt.

## Configuration

See `.env.example` for all variables.

```bash
export JARVIS_PROVIDER=anthropic
export JARVIS_MODEL=claude-sonnet-4-5
export JARVIS_MAX_STEPS=0
export JARVIS_STT=auto
export JARVIS_TTS=auto
export JARVIS_WAKE_WORD=jarvis
export JARVIS_VOICE=en-GB-RyanNeural
export JARVIS_SKILLS_DIR=~/.jarvis/skills
```

```bash
set -a; source .env.example; set +a
```

## How it works

```
jarvis/
  __main__.py        CLI, REPL, doctor, voice bootstrap
  agent.py           LLM <-> tool loop, approvals, live tool discovery
  tools.py           built-in tool registry, timers, memory
  tool.py            Tool dataclass + schema helper
  skills.py          load/create/delete self-written tools
  web.py             search, page fetching, arbitrary HTTP
  desktop.py         screenshots, mouse, keyboard, clipboard, power
  phone.py           Android control over ADB: UI, files, apps, shell
  policy.py          destructive-command approvals
  demo.py            scripted end-to-end self-test
  config.py          env/CLI configuration
  providers/         anthropic, openai, nvidia (NIM), ollama via stdlib HTTP
  voice/
    audio.py         microphone capture with energy VAD, playback
    stt.py           speech-to-text backends
    tts.py           text-to-speech backends
    assistant.py     wake word, conversation loop, spoken approvals
```

The agent is provider-agnostic: providers return normalized text and tool calls,
so a new backend just implements one `complete()` method. Tools are discovered
live, which is why a skill created mid-conversation works immediately.

## Troubleshooting

- **No speech-to-text backend** — `pip install faster-whisper`, or use
  `--stt openai` with a key.
- **No microphone** — install PortAudio, run `./jarvis.sh doctor`, pass
  `--input-device`.
- **No sound** — install `ffmpeg` (ffplay) or use `--tts espeak`.
- **Desktop tools fail** — `pip install pyautogui pillow pyperclip`, and make
  sure you are in a graphical session (`DISPLAY`/`WAYLAND_DISPLAY`).
- **Phone not found** — `sudo apt install adb`, enable USB debugging, accept
  the RSA prompt, then `./jarvis.sh doctor`. For multiple devices pass
  `serial`; for wireless use `phone_control` action `pair` then `connect`.
- **Wake word missed** — use `--no-wake`, or use a larger Whisper model.
- **A skill has an error** — `./jarvis.sh doctor` lists broken skills;
  delete the file in `~/.jarvis/skills/`.

## Safety

This assistant is intentionally powerful: shell, arbitrary Python, desktop
control, Android phone control (including root shell), network calls, and
self-written skills. Run it as a normal user, not as root. Review approval
prompts. If you enable `--unsafe`, use a VM or container for tasks you would not
hand to a stranger. Never put secrets in prompts or skill files that you do not
want sent to your model provider.
