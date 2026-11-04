import re

PATTERNS = [
    r"\brm\s+-\w*[rf]",
    r"\brm\s+--(recursive|force)",
    r"\brm\s+.*\s+/(\s|$)",
    r"\bmkfs(\.\w+)?\b",
    r"\bwipefs\b",
    r"\bfdisk\b",
    r"\bparted\b",
    r"\bsgdisk\b",
    r"\bdd\b.*\bof=",
    r">\s*/dev/(sd|nvme|mmcblk|vd)",
    r"\bshutdown\b",
    r"\breboot\b",
    r"\bpoweroff\b",
    r"\bhalt\b",
    r"\binit\s+0\b",
    r"\bsystemctl\s+(reboot|poweroff|halt|kexec)\b",
    r"\bkill(all)?\s+-9\s+1\b",
    r"\buserdel\b",
    r"\bgroupdel\b",
    r"\bpasswd\b",
    r"\bchmod\s+-R\s+777\s+/",
    r"\bchown\s+-R\s+.*\s+/",
    r"\bsudo\b",
    r"\b(curl|wget)\b[^|]*\|\s*(sudo\s+)?(sh|bash|zsh)\b",
    r":\(\)\s*\{.*\};?\s*:",
    r"\bhistory\s+-c\b",
    r"\b(iptables|nft)\s+-F\b",
    r"\bufw\s+disable\b",
    r"\bfind\b.*\s-delete\b",
    r"\btruncate\s+-s\s*0\b",
    r"\bmv\b.*\s/dev/null\b",
    r"\bdrop\s+database\b",
    r"\bgit\s+push\b.*--force",
    r"\bgit\s+reset\s+--hard\b",
    r"\b(python[0-9.]*|perl|ruby|node|php)\s+-[ce]\b",
    r"\b(bash|sh|zsh|fish)\s+-c\b",
    r"\beval\b",
    r"\bfind\b.*-exec\b.*\brm\b",
    r"\bcrontab\b",
]

_COMPILED = [re.compile(pattern, re.IGNORECASE) for pattern in PATTERNS]


def needs_command_approval(command: str) -> bool:
    """Return True for commands that can destroy data or the machine."""
    command = command or ""
    return any(pattern.search(command) for pattern in _COMPILED)
