import importlib.util
import os
import re
import uuid

from .tool import Tool, schema


def skills_dir() -> str:
    return os.path.expanduser(
        os.environ.get("JARVIS_SKILLS_DIR", "~/.jarvis/skills")
    )


def safe_name(name: str) -> str:
    cleaned = re.sub(r"[^a-zA-Z0-9_-]+", "_", name).strip("_").lower()
    return (cleaned or "skill")[:60]


def _to_tool(entry: object, source: str) -> Tool | None:
    if isinstance(entry, Tool):
        tool = entry
    elif isinstance(entry, dict):
        func = entry.get("func")
        input_schema = entry.get("input_schema") or entry.get("parameters")
        tool = Tool(
            name=str(entry.get("name", "")),
            description=str(entry.get("description", "")),
            input_schema=input_schema if isinstance(input_schema, dict) else schema({}),
            func=func,
            requires_approval=bool(entry.get("requires_approval", False)),
        )
    else:
        raise ValueError(f"{source}: each TOOLS entry must be a dict or Tool")

    if not tool.name:
        raise ValueError(f"{source}: tool is missing a name")
    if not callable(tool.func):
        raise ValueError(f"{source}: tool {tool.name!r} has no callable func")
    if not isinstance(tool.input_schema, dict):
        raise ValueError(f"{source}: tool {tool.name!r} input_schema must be a dict")
    return tool


def load_skill_file(path: str) -> list[Tool]:
    stem = os.path.splitext(os.path.basename(path))[0]
    module_name = f"jarvis_skill_{safe_name(stem)}_{uuid.uuid4().hex[:8]}"
    spec = importlib.util.spec_from_file_location(module_name, path)
    if spec is None or spec.loader is None:
        raise ValueError(f"cannot load skill file {path}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)

    entries = getattr(module, "TOOLS", None)
    if entries is None and hasattr(module, "register"):
        entries = module.register()
    if entries is None:
        return []
    if not isinstance(entries, (list, tuple)):
        raise ValueError(f"{path}: TOOLS must be a list")

    tools = []
    for entry in entries:
        tool = _to_tool(entry, os.path.basename(path))
        if tool is not None:
            tools.append(tool)
    return tools


def load_skills(errors: list[str] | None = None) -> list[Tool]:
    directory = skills_dir()
    if not os.path.isdir(directory):
        return []
    tools: list[Tool] = []
    for filename in sorted(os.listdir(directory)):
        if not filename.endswith(".py") or filename.startswith("_"):
            continue
        path = os.path.join(directory, filename)
        try:
            tools.extend(load_skill_file(path))
        except Exception as exc:
            if errors is not None:
                errors.append(f"{filename}: {exc}")
    return tools


def write_skill(name: str, description: str, code: str) -> tuple[str, list[Tool]]:
    directory = skills_dir()
    os.makedirs(directory, exist_ok=True)
    filename = safe_name(name) + ".py"
    path = os.path.join(directory, filename)
    header = f'"""Jarvis skill: {" ".join(description.split())[:200]}"""\n\n'
    with open(path, "w") as handle:
        handle.write(header)
        handle.write(code)
        if not code.endswith("\n"):
            handle.write("\n")
    try:
        tools = load_skill_file(path)
    except Exception:
        try:
            os.unlink(path)
        except OSError:
            pass
        raise
    return path, tools


def delete_skill_file(name: str) -> str:
    path = os.path.join(skills_dir(), safe_name(name) + ".py")
    if not os.path.exists(path):
        return f"No skill file named {name!r} was found."
    try:
        os.unlink(path)
        return (
            f"Deleted {path}. The skill's tools stay available until Jarvis restarts."
        )
    except OSError as exc:
        return f"Could not delete {path}: {exc}"


def list_skills() -> str:
    directory = skills_dir()
    if not os.path.isdir(directory) or not os.listdir(directory):
        return f"No skills are installed in {directory}."
    errors: list[str] = []
    tools = load_skills(errors)
    lines = [f"Skills directory: {directory}"]
    if tools:
        lines.append("Tools: " + ", ".join(sorted(tool.name for tool in tools)))
    else:
        lines.append("No loadable tools.")
    for error in errors:
        lines.append(f"Error: {error}")
    return "\n".join(lines)
