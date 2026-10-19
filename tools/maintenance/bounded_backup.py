"""Run one encrypted backup and its verification in a bounded Windows job.

Only this invocation's Python/age/tar/Docker-client children enter the job.
Docker archive helpers carry an unguessable per-attempt label and exact names.
Production service processes and existing containers never enter the cleanup set.
"""
import argparse
import contextlib
import ctypes
from ctypes import wintypes
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import time
import uuid

LABEL = "gying.maintenance.backup"


def read(path):
    return json.loads(Path(path).read_text(encoding="utf-8-sig"))


def save(path, value):
    target = Path(path)
    temp = target.with_name(target.name + "." + uuid.uuid4().hex + ".tmp")
    with temp.open("w", encoding="utf-8") as stream:
        json.dump(value, stream)
        stream.flush()
        os.fsync(stream.fileno())
    os.replace(temp, target)


class Job:
    def __init__(self):
        if os.name != "nt":
            raise RuntimeError("Windows job containment required")
        class Basic(ctypes.Structure):
            _fields_ = [("PerProcessUserTimeLimit", ctypes.c_longlong),
                        ("PerJobUserTimeLimit", ctypes.c_longlong),
                        ("LimitFlags", wintypes.DWORD), ("MinimumWorkingSetSize", ctypes.c_size_t),
                        ("MaximumWorkingSetSize", ctypes.c_size_t), ("ActiveProcessLimit", wintypes.DWORD),
                        ("Affinity", ctypes.c_size_t), ("PriorityClass", wintypes.DWORD),
                        ("SchedulingClass", wintypes.DWORD)]
        class Counters(ctypes.Structure):
            _fields_ = [(name, ctypes.c_ulonglong) for name in
                        ("ReadOperationCount", "WriteOperationCount", "OtherOperationCount",
                         "ReadTransferCount", "WriteTransferCount", "OtherTransferCount")]
        class Limits(ctypes.Structure):
            _fields_ = [("BasicLimitInformation", Basic), ("IoInfo", Counters),
                        ("ProcessMemoryLimit", ctypes.c_size_t), ("JobMemoryLimit", ctypes.c_size_t),
                        ("PeakProcessMemoryUsed", ctypes.c_size_t), ("PeakJobMemoryUsed", ctypes.c_size_t)]
        self.api = ctypes.WinDLL("kernel32", use_last_error=True)
        self.api.CreateJobObjectW.argtypes = [ctypes.c_void_p, wintypes.LPCWSTR]
        self.api.CreateJobObjectW.restype = wintypes.HANDLE
        self.api.SetInformationJobObject.argtypes = [wintypes.HANDLE, ctypes.c_int, ctypes.c_void_p, wintypes.DWORD]
        self.api.AssignProcessToJobObject.argtypes = [wintypes.HANDLE, wintypes.HANDLE]
        self.api.TerminateJobObject.argtypes = [wintypes.HANDLE, wintypes.UINT]
        self.api.CloseHandle.argtypes = [wintypes.HANDLE]
        self.handle = self.api.CreateJobObjectW(None, None)
        if not self.handle:
            raise ctypes.WinError(ctypes.get_last_error())
        limits = Limits()
        limits.BasicLimitInformation.LimitFlags = 0x2000  # JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE
        if not self.api.SetInformationJobObject(self.handle, 9, ctypes.byref(limits), ctypes.sizeof(limits)):
            self.close()
            raise ctypes.WinError(ctypes.get_last_error())

    def assign(self, child):
        if not self.api.AssignProcessToJobObject(self.handle, wintypes.HANDLE(int(child._handle))):
            raise ctypes.WinError(ctypes.get_last_error())

    def terminate(self):
        if not self.api.TerminateJobObject(self.handle, 124):
            raise ctypes.WinError(ctypes.get_last_error())

    def close(self):
        if self.handle:
            self.api.CloseHandle(self.handle)
            self.handle = None


def load_module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def worker(config, folder, fixture=False):
    state = read(folder / "worker.json")
    save(folder / "ready.json", {"pid": os.getpid(), "token": state["token"]})
    deadline = time.monotonic() + 30
    while not (folder / "start.json").exists():
        if time.monotonic() >= deadline:
            raise RuntimeError("Supervisor did not assign job containment")
        time.sleep(0.05)
    if read(folder / "start.json")["token"] != state["token"]:
        raise RuntimeError("Containment handshake mismatch")
    if fixture:
        # Test-only child proves descendants are contained, without services.
        child = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(90)"],
                                 creationflags=subprocess.CREATE_NO_WINDOW)
        save(folder / "fixture-child.json", {"pid": child.pid})
        child.wait()
        return
    c = read(config)
    backup = load_module("bounded_backup_source", c["backupScript"])
    original = backup.encrypted_command

    def encrypted(command, destination, recipient, age):
        state["directory"] = str(destination.parent)
        if len(command) > 1 and command[1] == "run":
            name = "gying-backup-" + state["token"][:16] + "-" + str(len(state["helpers"]))
            state["helpers"].append(name)
            command = [command[0], "run", "--name", name, "--label", LABEL + "=" + state["token"], *command[2:]]
        save(folder / "worker.json", state)
        return original(command, destination, recipient, age)

    backup.encrypted_command = encrypted
    sys.argv = [c["backupScript"], "--config", c["backupConfig"], "--execute"]
    output = io.StringIO()
    with contextlib.redirect_stdout(output):
        backup.main()
    result = json.loads(output.getvalue().splitlines()[-1])
    verifier = load_module("bounded_backup_verifier", c["verifyBackupScript"])
    count = verifier.verify(result["directory"])
    root = Path(result["directory"]).resolve(strict=True)
    for entry in read(root / "manifest.json")["files"]:
        path = (root / entry["name"]).resolve(strict=True)
        if not path.is_relative_to(root) or path.suffix != ".age":
            raise RuntimeError("Unsafe backup artifact path")
        verified = subprocess.run([c["age"], "-d", "-i", c["ageIdentity"], str(path)],
                                  stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        if verified.returncode:
            raise RuntimeError("Backup authenticated decryption failed")
    result["verified"] = "SHA-256 and authenticated age decryption to null"
    result["verifiedArtifacts"] = count
    save(folder / "result.json", result)


def cleanup_helpers(c, state):
    """Delete no volumes/data. Kill only this failed backup's readonly tar helpers."""
    raw = subprocess.run([c["docker"], "ps", "-aq", "--filter", "label=" + LABEL + "=" + state["token"]],
                         capture_output=True, timeout=20, check=True).stdout.decode().split()
    for cid in raw:
        fmt = '{{json .Name}}|{{json .Config.Labels}}|{{json .HostConfig.ReadonlyRootfs}}|{{json .HostConfig.NetworkMode}}|{{json .Mounts}}'
        result = subprocess.run([c["docker"], "inspect", "--format", fmt, cid],
                                capture_output=True, timeout=20, check=True).stdout.decode().strip()
        name, labels, readonly, network, mounts = [json.loads(x) for x in result.split("|", 4)]
        if (name.lstrip("/") not in state["helpers"] or labels.get(LABEL) != state["token"] or
                not readonly or network != "none" or any(m.get("RW") for m in mounts)):
            raise RuntimeError("Backup helper identity/readonly proof changed; cleanup refused")
        # Only an exact, newly-created read-only archive helper is eligible.
        subprocess.run([c["docker"], "kill", "--signal", "SIGKILL", cid],
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=20)
        subprocess.run([c["docker"], "rm", cid], stdout=subprocess.DEVNULL,
                       stderr=subprocess.DEVNULL, timeout=20)


def supervise(config, folder, timeout_seconds=900, fixture=False):
    folder.mkdir(parents=True, exist_ok=False)
    token = uuid.uuid4().hex
    save(folder / "worker.json", {"token": token, "helpers": []})
    job = Job()
    child = None
    status = "failed"
    try:
        args = [sys.executable, "-X", "utf8", str(Path(__file__).resolve()),
                "--config", str(config), "--folder", str(folder), "--worker"]
        if fixture:
            args.append("--fixture")
        child = subprocess.Popen(args, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                                 creationflags=subprocess.CREATE_NO_WINDOW)
        # Worker waits for our token; it cannot spawn any backup children yet.
        job.assign(child)
        save(folder / "start.json", {"token": token})
        try:
            code = child.wait(timeout=timeout_seconds)
        except subprocess.TimeoutExpired:
            status = "timed_out"
            job.terminate()
            child.wait(timeout=20)
            raise RuntimeError("Backup exceeded its 15-minute budget")
        if code:
            raise RuntimeError("Contained backup/verification failed")
        result = read(folder / "result.json")
        if result.get("status") != "complete" or not result.get("verifiedArtifacts"):
            raise RuntimeError("Backup verification receipt missing")
        status = "complete"
        return result
    finally:
        # Closing the job also ends leaked transient descendants, never services.
        if child is not None and child.poll() is None:
            # If assignment failed, this child is still waiting for our handshake.
            child.terminate()
            child.wait(timeout=20)
        job.close()
        state = read(folder / "worker.json")
        report = {"status": status, "token": token, "budgetSeconds": timeout_seconds}
        save(folder / "supervisor.json", report)
        if status != "complete":
            if state.get("directory"):
                manifest_path = Path(state["directory"]) / "manifest.json"
                manifest = read(manifest_path)
                manifest["status"] = "maintenance_aborted"
                manifest["maintenanceAbort"] = status
                save(manifest_path, manifest)
            if not fixture:
                try:
                    cleanup_helpers(read(config), state)
                except Exception as error:
                    report["cleanupError"] = type(error).__name__
                    save(folder / "supervisor.json", report)
                    raise


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--config", required=True)
    parser.add_argument("--folder", required=True)
    parser.add_argument("--worker", action="store_true")
    parser.add_argument("--fixture", action="store_true")
    args = parser.parse_args()
    if args.worker:
        worker(args.config, Path(args.folder), args.fixture)
    else:
        print(json.dumps(supervise(args.config, Path(args.folder), fixture=args.fixture)))


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print("Bounded backup failed: " + type(error).__name__, file=sys.stderr)
        sys.exit(1)
