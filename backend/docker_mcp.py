import json
import os
import shlex
import subprocess
from typing import Any, Dict, List, Optional

from mcp.server.fastmcp import FastMCP

mcp = FastMCP("docker-tools")


def _run(cmd: List[str], cwd: Optional[str] = None, timeout: int = 120) -> Dict[str, Any]:
    try:
        p = subprocess.run(
            cmd,
            cwd=cwd,
            capture_output=True,
            text=True,
            timeout=timeout,
            shell=False,
        )
        return {
            "cmd": cmd,
            "returncode": p.returncode,
            "stdout": p.stdout.strip(),
            "stderr": p.stderr.strip(),
            "ok": p.returncode == 0,
        }
    except FileNotFoundError:
        return {
            "cmd": cmd,
            "returncode": -1,
            "stdout": "",
            "stderr": "docker 命令未找到，请确认 Docker Desktop 已启动且 docker 命令可用",
            "ok": False,
        }
    except subprocess.TimeoutExpired:
        return {
            "cmd": cmd,
            "returncode": -2,
            "stdout": "",
            "stderr": f"命令超时：{timeout}s",
            "ok": False,
        }


@mcp.tool()
def docker_ps(all_containers: bool = True) -> Dict[str, Any]:
    """列出容器。"""
    cmd = ["docker", "ps"]
    if all_containers:
        cmd.append("-a")
    cmd += ["--format", "{{.ID}}\t{{.Image}}\t{{.Status}}\t{{.Names}}"]
    result = _run(cmd, timeout=60)
    if not result["ok"]:
        return result

    items = []
    for line in result["stdout"].splitlines():
        parts = line.split("\t")
        if len(parts) >= 4:
            items.append({
                "id": parts[0],
                "image": parts[1],
                "status": parts[2],
                "name": parts[3],
            })
    return {"ok": True, "items": items}


@mcp.tool()
def docker_logs(container: str, tail: int = 200) -> Dict[str, Any]:
    """查看容器日志。"""
    return _run(["docker", "logs", "--tail", str(tail), container], timeout=60)


@mcp.tool()
def docker_start(container: str) -> Dict[str, Any]:
    """启动容器。"""
    return _run(["docker", "start", container], timeout=60)


@mcp.tool()
def docker_stop(container: str) -> Dict[str, Any]:
    """停止容器。"""
    return _run(["docker", "stop", container], timeout=60)


@mcp.tool()
def docker_rm(container: str, force: bool = False) -> Dict[str, Any]:
    """删除容器。"""
    cmd = ["docker", "rm"]
    if force:
        cmd.append("-f")
    cmd.append(container)
    return _run(cmd, timeout=60)


@mcp.tool()
def docker_run(
    image: str,
    name: Optional[str] = None,
    detach: bool = True,
    ports: Optional[List[str]] = None,
    volumes: Optional[List[str]] = None,
    env: Optional[List[str]] = None,
    command: Optional[List[str]] = None,
    extra_args: Optional[List[str]] = None,
) -> Dict[str, Any]:
    """
    启动一个新容器。
    ports 示例：["9000:9000", "9001:9001"]
    volumes 示例：["D:/data/minio:/data"]
    env 示例：["MINIO_ROOT_USER=admin", "MINIO_ROOT_PASSWORD=<password>"]
    command 示例：["minio", "server", "/data", "--console-address", ":9001"]
    """
    cmd = ["docker", "run"]
    if detach:
        cmd.append("-d")
    if name:
        cmd += ["--name", name]
    for p in ports or []:
        cmd += ["-p", p]
    for v in volumes or []:
        cmd += ["-v", v]
    for e in env or []:
        cmd += ["-e", e]
    for a in extra_args or []:
        cmd.append(a)
    cmd.append(image)
    for c in command or []:
        cmd.append(c)
    return _run(cmd, timeout=180)


@mcp.tool()
def compose_ps(project_dir: str) -> Dict[str, Any]:
    """查看 compose 项目状态。"""
    return _run(["docker", "compose", "ps"], cwd=project_dir, timeout=60)


@mcp.tool()
def compose_up(project_dir: str, detach: bool = True, services: Optional[List[str]] = None) -> Dict[str, Any]:
    """启动 compose。"""
    cmd = ["docker", "compose", "up"]
    if detach:
        cmd.append("-d")
    for s in services or []:
        cmd.append(s)
    return _run(cmd, cwd=project_dir, timeout=180)


@mcp.tool()
def compose_down(project_dir: str, remove_volumes: bool = False) -> Dict[str, Any]:
    """停止并移除 compose。"""
    cmd = ["docker", "compose", "down"]
    if remove_volumes:
        cmd.append("-v")
    return _run(cmd, cwd=project_dir, timeout=180)


if __name__ == "__main__":
    mcp.run()
