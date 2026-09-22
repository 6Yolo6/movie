"""Verify encrypted backup integrity only; never decrypt or restore production automatically."""
import argparse
import hashlib
import json
from pathlib import Path


def verify(directory):
    root=Path(directory).resolve(strict=True)
    manifest=json.loads((root/"manifest.json").read_text(encoding="utf-8"))
    if manifest.get("status")!="complete" or not manifest.get("files"): raise ValueError("Backup is incomplete")
    for entry in manifest["files"]:
        path=(root/entry["name"]).resolve(strict=True)
        if not path.is_relative_to(root) or path.suffix!=".age": raise ValueError("Unsafe manifest path")
        digest=hashlib.sha256()
        with path.open("rb") as stream:
            for block in iter(lambda:stream.read(1024*1024),b""): digest.update(block)
        if path.stat().st_size!=entry["size"] or digest.hexdigest()!=entry["sha256"]:
            raise ValueError("Backup checksum mismatch")
    return len(manifest["files"])


if __name__=="__main__":
    parser=argparse.ArgumentParser(); parser.add_argument("directory"); args=parser.parse_args()
    try: print(f"Verified {verify(args.directory)} encrypted artifacts. This does not prove decryptability or restore success.")
    except Exception as error: raise SystemExit("Verification failed: "+type(error).__name__)
