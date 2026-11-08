"""Extract literal Spring mappings; fail on expressions rather than guessing routes."""
from __future__ import annotations

import hashlib
import json
import re
import subprocess
from datetime import date
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CONTROLLERS = ROOT / "backend/src/main/java/com/gying/movie/controller"
MAPPINGS = re.compile(r"@(?:org\.springframework\.web\.bind\.annotation\.)?(Get|Post|Put|Delete|Patch|Request)Mapping\b")
GUARDS = re.compile(r"\b(\w+)\.(requireAdmin|requireUser|requireInternal|requireWebhookToken|require)\s*\(")


def code_mask(source: str) -> str:
    """Preserve offsets/newlines while hiding Java strings and comments."""
    result = list(source)
    i = 0
    while i < len(source):
        end = None
        if source.startswith("//", i):
            end = source.find("\n", i)
            if end < 0:
                end = len(source)
        elif source.startswith("/*", i):
            close = source.find("*/", i + 2)
            if close < 0:
                raise ValueError("Unterminated comment")
            end = close + 2
        elif source.startswith('"""', i):
            close = source.find('"""', i + 3)
            if close < 0:
                raise ValueError("Unterminated text block")
            end = close + 3
        elif source[i] in {'"', "'"}:
            quote = source[i]
            end = i + 1
            while end < len(source):
                if source[end] == "\\":
                    end += 2
                elif source[end] == quote:
                    end += 1
                    break
                else:
                    end += 1
            else:
                raise ValueError("Unterminated Java string")
        if end is None:
            i += 1
        else:
            for j in range(i, end):
                if source[j] != "\n":
                    result[j] = " "
            i = end
    return "".join(result)


def close_at(mask: str, start: int, opening: str = "(", closing: str = ")") -> int:
    if mask[start] != opening:
        raise ValueError("Missing opening delimiter")
    depth = 0
    for i in range(start, len(mask)):
        if mask[i] == opening:
            depth += 1
        elif mask[i] == closing:
            depth -= 1
            if depth == 0:
                return i
    raise ValueError("Unbalanced Java delimiter")


def split_args(value: str) -> list[str]:
    mask = code_mask(value)
    parts, start, depth = [], 0, 0
    for i, char in enumerate(mask):
        if char in "{([":
            depth += 1
        elif char in "})]":
            depth -= 1
        elif char == "," and depth == 0:
            parts.append(value[start:i].strip())
            start = i + 1
    parts.append(value[start:].strip())
    return [part for part in parts if part]


def literals(value: str) -> list[str]:
    value = value.strip()
    if value.startswith("{") and value.endswith("}"):
        value = value[1:-1].strip()
    result = []
    for part in split_args(value):
        if not re.fullmatch(r'"(?:[^"\\]|\\.)*"', part, re.S):
            raise ValueError(f"Nonliteral route expression: {part}")
        result.append(json.loads(part))
    return result


def mapping_values(kind: str, args: str) -> tuple[list[str], list[str]]:
    values = {}
    for item in split_args(args):
        if item.startswith('"') or item.startswith("{"):
            key, value = "value", item
        elif "=" in item:
            key, value = item.split("=", 1)
            key = key.strip()
        else:
            raise ValueError(f"Unsupported mapping argument: {item}")
        if key in values:
            raise ValueError(f"Duplicate mapping argument: {key}")
        values[key] = value.strip()
    if "value" in values and "path" in values:
        raise ValueError("Ambiguous value/path mapping")
    paths = literals(values.get("path", values.get("value", '""')))
    if not paths:
        paths = [""]
    if kind != "Request":
        return paths, [kind.upper()]
    if "method" not in values:
        return paths, ["ANY"]
    method_value = values["method"].strip().strip("{}").strip()
    methods = []
    for item in split_args(method_value):
        match = re.fullmatch(r"(?:org\.springframework\.web\.bind\.annotation\.)?RequestMethod\.(GET|POST|PUT|DELETE|PATCH|HEAD|OPTIONS|TRACE)", item)
        if not match:
            raise ValueError(f"Nonliteral HTTP method: {item}")
        methods.append(match.group(1))
    return paths, methods


def extract(file: Path) -> tuple[dict, list[dict]]:
    raw = file.read_bytes()
    source = raw.decode("utf-8-sig")
    mask = code_mask(source)
    class_match = re.search(r"\bclass\s+(\w+)\b", mask)
    if not class_match:
        raise ValueError(f"Controller class declaration missing: {file}")
    name = class_match.group(1)
    bases = [""]
    routes = []
    annotation_count = 0
    line = lambda offset: source.count("\n", 0, offset) + 1
    source_path = file.relative_to(ROOT).as_posix()
    for annotation in MAPPINGS.finditer(mask):
        pos = annotation.end()
        while pos < len(mask) and mask[pos].isspace():
            pos += 1
        args = ""
        if pos < len(mask) and mask[pos] == "(":
            end = close_at(mask, pos)
            args = source[pos + 1:end]
            pos = end + 1
        paths, methods = mapping_values(annotation.group(1), args)
        if annotation.start() < class_match.start():
            if annotation.group(1) != "Request":
                raise ValueError("Non-RequestMapping class annotation")
            bases = paths
            continue
        annotation_count += 1
        declaration = re.search(r"\bpublic\s+[^;{}=]*?\b(\w+)\s*\(", mask[pos:])
        if not declaration:
            raise ValueError(f"Mapped public method missing: {source_path}:{line(pos)}")
        declaration_start = pos + declaration.start()
        method_name = declaration.group(1)
        parameters_start = pos + declaration.end() - 1
        parameters_end = close_at(mask, parameters_start)
        body_start = mask.find("{", parameters_end)
        if body_start < 0:
            raise ValueError(f"Mapped method body missing: {method_name}")
        body_end = close_at(mask, body_start, "{", "}")
        guards = [{"receiver": guard.group(1), "method": guard.group(2), "line": line(body_start + guard.start())}
                  for guard in GUARDS.finditer(mask[body_start:body_end])]
        calls = sorted({guard["method"] for guard in guards})
        for base in bases:
            for child in paths:
                route_path = "/" + "/".join(part.strip("/") for part in [base, child] if part.strip("/"))
                for method in methods:
                    route = {"method": method, "path": route_path, "controller": name, "methodName": method_name,
                             "source": source_path, "line": line(annotation.start()), "methodLine": line(declaration_start),
                             "directGuardCalls": calls, "directGuards": guards, "authorizationReviewRequired": True}
                    if "requireAdmin" in calls:
                        route["authorizationHint"] = "ADMIN (direct requireAdmin)"
                    routes.append(route)
    return {"name": name, "source": source_path, "sha256": hashlib.sha256(raw).hexdigest(), "mappingAnnotations": annotation_count}, routes


def generate() -> dict:
    controllers, routes = [], []
    for file in sorted(CONTROLLERS.glob("*Controller.java")):
        controller, found = extract(file)
        controllers.append(controller)
        routes.extend(found)
    routes.sort(key=lambda route: (route["path"], route["method"], route["controller"]))
    identities = [(route["method"], route["path"]) for route in routes]
    if len(identities) != len(set(identities)):
        raise ValueError("Duplicate HTTP method/path mapping")
    git = lambda *args: subprocess.check_output(["git", *args], cwd=ROOT, text=True).strip()
    inventory = {"generatedDate": date.today().isoformat(), "sourceCommit": git("rev-parse", "HEAD"),
                 "sourceBranch": git("branch", "--show-current"), "sourceState": "working-tree",
                 "sourceWorkingTreeChanges": git("status", "--short", "--", str(CONTROLLERS.relative_to(ROOT))).splitlines(),
                 "scope": "backend Spring controller literal mapping annotations from the current working tree; sourceCommit is the Git baseline, controller SHA-256 identifies the actual source; not dynamic authorization validation or deployment evidence",
                 "generator": "tools/security/generate_api_inventory.py", "controllerCount": len(controllers),
                 "mappingAnnotationCount": sum(item["mappingAnnotations"] for item in controllers), "routeCount": len(routes),
                 "controllers": controllers, "routes": routes}
    target = ROOT / "docs/security/api-inventory.json"
    target.write_text(json.dumps(inventory, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return inventory


if __name__ == "__main__":
    result = generate()
    print(json.dumps({key: result[key] for key in ["controllerCount", "mappingAnnotationCount", "routeCount"]}))
