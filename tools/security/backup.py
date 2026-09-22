"""Encrypted streaming backups for Windows + Docker Desktop. No plaintext SQL/volume archives.
Requires native age, tar and mysqldump; independent backup DB credentials stay in an ACL-protected file.
"""
import argparse
import datetime
import hashlib
import json
import re
import subprocess
import tempfile
from pathlib import Path


def encrypted_command(command, destination, recipient, age):
    with tempfile.TemporaryFile() as errors, destination.open("xb") as output:
        producer=subprocess.Popen(command,stdout=subprocess.PIPE,stderr=errors)
        encryptor=None
        try:
            encryptor=subprocess.Popen([age,"-r",recipient],stdin=producer.stdout,stdout=output,stderr=errors)
            producer.stdout.close()
            code=encryptor.wait(timeout=7200)
            source_code=producer.wait(timeout=60)
            if code or source_code: raise RuntimeError("Backup/encryption process failed; incomplete artifacts are not valid backups")
        finally:
            for child in (producer,encryptor):
                if child is not None and child.poll() is None: child.kill(); child.wait()
    digest=hashlib.sha256()
    with destination.open("rb") as stream:
        for block in iter(lambda:stream.read(1024*1024),b""): digest.update(block)
    return {"name":destination.name,"size":destination.stat().st_size,"sha256":digest.hexdigest()}


def main():
    parser=argparse.ArgumentParser(); parser.add_argument("--config",required=True); parser.add_argument("--execute",action="store_true")
    args=parser.parse_args(); cfg=json.loads(Path(args.config).read_text(encoding="utf-8-sig"))
    repo=Path(__file__).resolve().parents[2]
    output=Path(cfg["output_root"]).resolve()
    if output==repo or output.is_relative_to(repo): raise ValueError("Backup output must be outside the Git workspace")
    database=cfg.get("database","gying")
    if not re.fullmatch(r"[A-Za-z0-9_]{1,64}",database): raise ValueError("Invalid database identifier")
    recipient=Path(cfg["age_recipient_file"]).read_text().strip()
    if not re.fullmatch(r"age1[0-9a-z]+",recipient): raise ValueError("Use one age public recipient, never a private identity here")
    defaults=Path(cfg["mysql_defaults_file"]).resolve(strict=True)
    if defaults.is_relative_to(repo): raise ValueError("MySQL backup credentials must be outside the repository")
    age=cfg.get("age","age"); tar=cfg.get("tar","tar"); docker=cfg.get("docker","docker")
    commands=[("mysql.sql.age",[cfg.get("mysqldump","mysqldump"),f"--defaults-extra-file={defaults}",
        "--single-transaction","--quick","--skip-lock-tables","--no-tablespaces","--hex-blob",
        "--default-character-set=utf8mb4","--set-gtid-purged=OFF","--routines","--events","--triggers",database])]
    for entry in cfg.get("paths",[]):
        name=entry["name"]; source=Path(entry["path"]).resolve(strict=True)
        if not re.fullmatch(r"[A-Za-z0-9_-]+",name): raise ValueError("Invalid archive name")
        commands.append((name+".tar.age",[tar,"-cf","-","-C",str(source.parent),source.name]))
    for entry in cfg.get("docker_volumes",[]):
        name=entry["name"]; volume=entry["volume"]
        if not re.fullmatch(r"[A-Za-z0-9_-]+",name) or not re.fullmatch(r"[A-Za-z0-9_.-]+",volume): raise ValueError("Invalid volume/archive name")
        # Verify existence: Docker otherwise silently creates an empty volume and produces a misleading backup.
        subprocess.run([docker,"volume","inspect",volume],check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
        commands.append((name+".tar.age",[docker,"run","--rm","--network","none","--read-only","--cap-drop","ALL",
            "--cap-add","DAC_READ_SEARCH","--security-opt","no-new-privileges:true","--mount",f"type=volume,src={volume},dst=/source,readonly",
            cfg.get("tar_image","busybox:1.37"),"tar","-cf","-","-C","/source","."]))
    names=[name for name,_ in commands]
    if len(names)!=len(set(names)): raise ValueError("Duplicate archive names")
    if not args.execute:
        print(json.dumps({"mode":"dry-run","output_root":str(output),"artifacts":names,"plaintext_archives":False})); return
    now=datetime.datetime.now(datetime.timezone.utc)
    destination=output/now.strftime("%Y%m%dT%H%M%S.%fZ")
    destination.mkdir(parents=True,exist_ok=False)
    manifest={"status":"incomplete","created_at":now.isoformat(),"database":database,
              "consistency":"MySQL single transaction; live volume snapshots require a quiesced maintenance window for cross-service consistency", "files":[]}
    path=destination/"manifest.json"
    path.write_text(json.dumps(manifest,indent=2),encoding="utf-8")
    try:
        for name,command in commands:
            manifest["files"].append(encrypted_command(command,destination/name,recipient,age))
        manifest["status"]="complete"
    finally:
        path.write_text(json.dumps(manifest,indent=2),encoding="utf-8")
    print(json.dumps({"status":"complete","directory":str(destination),"encrypted_artifacts":len(manifest["files"])}))
    # Deliberately no retention deletion. Prune only verified generations after checking an offline copy.


if __name__=="__main__":
    try: main()
    except (KeyboardInterrupt,SystemExit): raise
    except Exception as error: raise SystemExit("Backup failed ("+type(error).__name__+"); check the incomplete manifest/task result")
