from dataclasses import dataclass
from typing import Any, Callable


def schema(properties: dict[str, Any], required: list[str] | None = None) -> dict:
    return {
        "type": "object",
        "properties": properties,
        "required": required or [],
        "additionalProperties": False,
    }


@dataclass
class Tool:
    name: str
    description: str
    input_schema: dict
    func: Callable[..., str]
    requires_approval: bool = False

    def spec(self) -> dict:
        return {
            "name": self.name,
            "description": self.description,
            "input_schema": self.input_schema,
        }
