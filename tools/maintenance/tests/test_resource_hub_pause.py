import datetime as dt
import importlib.util
import pathlib
import unittest
import hashlib
import json
import os
import subprocess
import tempfile
import copy
import contextlib
import xml.etree.ElementTree as ET
from unittest import mock

PATH = pathlib.Path(__file__).resolve().parents[1] / "resource_hub_pause.py"
SPEC = importlib.util.spec_from_file_location("pause", PATH)
PAUSE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PAUSE)


def guard_proof(config, helper, config_path, operation, name):
    namespace = "http://schemas.microsoft.com/windows/2004/02/mit/task"
    ET.register_namespace("", namespace)
    def element(parent, tag, text=None, **attributes):
        child = ET.SubElement(parent, "{" + namespace + "}" + tag, attributes)
        child.text = text
        return child
    root = ET.Element("{" + namespace + "}Task", {"version":"1.4"})
    element(element(root, "RegistrationInfo"), "URI", "\\" + name)
    principal = element(element(root, "Principals"), "Principal", id="Author")
    sid = "S-1-5-21-1-2-3-1001"
    for key, value in {"UserId":sid,"LogonType":"InteractiveToken","RunLevel":"HighestAvailable"}.items():
        element(principal,key,value)
    trigger = element(element(root,"Triggers"),"LogonTrigger")
    for key,value in {"UserId":config["host"]+"\\ASUS","Enabled":"true","Delay":"PT2M"}.items():
        element(trigger,key,value)
    action = element(element(root,"Actions",Context="Author"),"Exec")
    for key,value in {"Command":config["python"],"Arguments":PAUSE.guard_arguments(helper,config_path,operation),
                      "WorkingDirectory":str(pathlib.Path(helper).parent)}.items():
        element(action,key,value)
    settings=element(root,"Settings")
    for key,value in {"Enabled":"true","MultipleInstancesPolicy":"IgnoreNew","ExecutionTimeLimit":"PT0S",
                      "StartWhenAvailable":"true","AllowHardTerminate":"false","DisallowStartIfOnBatteries":"false",
                      "StopIfGoingOnBatteries":"false","WakeToRun":"false","RunOnlyIfIdle":"false",
                      "RunOnlyIfNetworkAvailable":"false"}.items():
        element(settings,key,value)
    retry=element(settings,"RestartOnFailure")
    element(retry,"Interval","PT2M")
    element(retry,"Count","10")
    return {"name":name,"path":"\\","expectedSid":sid,"xml":ET.tostring(root,encoding="unicode")}



class RestartRegistrationBindingTests(unittest.TestCase):
    def setUp(self):
        self.start=dt.datetime.now(dt.timezone(dt.timedelta(hours=8))).replace(microsecond=0)+dt.timedelta(minutes=10)
        self.permit={"id":"bound-permit","startAt":self.start.isoformat(),
                     "latestStartAt":(self.start+dt.timedelta(minutes=10)).isoformat(),
                     "finishBefore":(self.start+dt.timedelta(hours=1)).isoformat()}
        self.result=(self.permit,*(dt.datetime.fromisoformat(self.permit[key]) for key in ("startAt","latestStartAt","finishBefore")))
        self.registration={"mode":"registered","task":"GYing One-off Graceful Restart "+self.start.strftime("%Y%m%d-%H%M"),
                           **{key:self.permit[key] for key in ("startAt","latestStartAt","finishBefore")}}
    def test_unused_future_registration_bound_to_permit_is_accepted(self):
        self.assertEqual(PAUSE.validate_restart_registration(self.registration,self.result),self.permit)
    def test_wrong_task_window_missing_field_or_consumed_permit_is_refused(self):
        variants=[{**self.registration,"task":"different restart"},{**self.registration,"mode":"dry-run"}]
        variants.extend({**self.registration,key:self.start.isoformat()+"0"} for key in ("startAt","latestStartAt","finishBefore"))
        variants.append({key:value for key,value in self.registration.items() if key!="finishBefore"})
        for registration in variants:
            with self.subTest(registration=registration),self.assertRaises(RuntimeError):
                PAUSE.validate_restart_registration(registration,self.result)
        for used in (None,"2026-10-27T01:00:00+00:00"):
            with self.subTest(used=used),self.assertRaises(RuntimeError):
                PAUSE.validate_restart_registration(self.registration,({**self.permit,"usedAt":used},*self.result[1:]))
    def test_expired_verified_permit_cannot_prepare_new_worker_pause(self):
        with self.assertRaises(RuntimeError):
            PAUSE.validate_restart_registration(self.registration,(self.permit,self.start-dt.timedelta(days=1),*self.result[2:]))


class ExactGuardTaskTests(unittest.TestCase):
    def setUp(self):
        self.config={"host":"HOST","python":r"C:\Python310\python.exe"}
        self.helper=PATH
        self.config_path=PATH.parent/"fixture-config.json"
        self.operation=PATH.parent/"fixture-operation.json"
        self.name="GYing Resource Hub Maintenance Restore fixture"
        self.proof=guard_proof(self.config,self.helper,self.config_path,self.operation,self.name)
        self.namespace={"t":"http://schemas.microsoft.com/windows/2004/02/mit/task"}
    def validate(self,proof):
        return PAUSE.validate_guard_task(proof,self.config,self.helper,self.config_path,self.operation,self.name)
    def changed(self,path,value=None,append=False,remove=False):
        proof=copy.deepcopy(self.proof)
        root=ET.fromstring(proof["xml"])
        node=root.find(path,self.namespace)
        if append:
            node.append(copy.deepcopy(node[0]))
        elif remove:
            node.clear()
        else:
            node.text=value
        proof["xml"]=ET.tostring(root,encoding="unicode")
        return proof
    def test_full_exact_guard_is_accepted_with_sid_or_named_owner(self):
        self.validate(self.proof)
        self.validate(self.changed("t:Principals/t:Principal/t:UserId","HOST\\ASUS"))
    def test_native_export_omitted_enabled_and_wake_defaults_are_accepted(self):
        proof=copy.deepcopy(self.proof)
        root=ET.fromstring(proof["xml"])
        settings=root.find("t:Settings",self.namespace)
        for name in ("Enabled","WakeToRun"):
            settings.remove(settings.find("t:"+name,self.namespace))
        proof["xml"]=ET.tostring(root,encoding="unicode")
        self.validate(proof)
        for name in ("Enabled","WakeToRun"):
            changed=copy.deepcopy(proof)
            altered=ET.fromstring(changed["xml"])
            element=ET.SubElement(altered.find("t:Settings",self.namespace),"{"+self.namespace["t"]+"}"+name)
            element.text="false" if name=="Enabled" else "true"
            changed["xml"]=ET.tostring(altered,encoding="unicode")
            with self.subTest(name=name),self.assertRaises(RuntimeError):self.validate(changed)
    def test_duplicate_default_nodes_are_refused(self):
        for name in ("Enabled","WakeToRun"):
            proof=copy.deepcopy(self.proof)
            root=ET.fromstring(proof["xml"])
            settings=root.find("t:Settings",self.namespace)
            settings.append(copy.deepcopy(settings.find("t:"+name,self.namespace)))
            proof["xml"]=ET.tostring(root,encoding="unicode")
            with self.subTest(name=name),self.assertRaises(RuntimeError):self.validate(proof)
    def test_extra_arguments_and_wrong_executable_or_working_directory_are_refused(self):
        for path,value in [("t:Actions/t:Exec/t:Arguments",str(self.operation)),
                           ("t:Actions/t:Exec/t:Arguments",PAUSE.guard_arguments(self.helper,self.config_path,self.operation)+" --pause"),
                           ("t:Actions/t:Exec/t:Command","other-python.exe"),
                           ("t:Actions/t:Exec/t:WorkingDirectory","other-runtime")]:
            with self.subTest(path=path,value=value),self.assertRaisesRegex(RuntimeError,"exact bound ASUS"):
                self.validate(self.changed(path,value))
    def test_different_principal_trigger_and_task_identities_are_refused(self):
        variations=[self.changed("t:Principals/t:Principal/t:UserId","HOST\\Other"),
                    self.changed("t:Principals/t:Principal/t:LogonType","Password"),
                    self.changed("t:Principals/t:Principal/t:RunLevel","LeastPrivilege"),
                    self.changed("t:Triggers/t:LogonTrigger/t:UserId","HOST\\Other"),
                    self.changed("t:Triggers/t:LogonTrigger/t:Delay","PT90S"),
                    self.changed("t:Triggers/t:LogonTrigger/t:Enabled","false"),
                    self.changed("t:RegistrationInfo/t:URI","\\other")]
        variations.extend([{**self.proof,"name":"other"},{**self.proof,"path":"\\nested\\"},
                           {**self.proof,"expectedSid":"not-an-account-sid"},{**self.proof,"xml":"malformed"}])
        for proof in variations:
            with self.subTest(proof=proof),self.assertRaises(RuntimeError):self.validate(proof)
    def test_duplicate_actions_principals_and_triggers_are_refused(self):
        for path in ("t:Actions","t:Principals","t:Triggers","t:Settings/t:RestartOnFailure"):
            with self.subTest(path=path),self.assertRaises(RuntimeError):
                self.validate(self.changed(path,append=True))
    def test_all_recovery_settings_are_checked(self):
        changes={"Enabled":"false","MultipleInstancesPolicy":"Parallel","ExecutionTimeLimit":"PT3H",
                 "StartWhenAvailable":"false","AllowHardTerminate":"true","DisallowStartIfOnBatteries":"true",
                 "StopIfGoingOnBatteries":"true","WakeToRun":"true","RunOnlyIfIdle":"true",
                 "RunOnlyIfNetworkAvailable":"true","RestartOnFailure/Interval":"PT1M","RestartOnFailure/Count":"1"}
        for path,value in changes.items():
            path="t:Settings/"+"/".join("t:"+part for part in path.split("/"))
            with self.subTest(path=path),self.assertRaises(RuntimeError):self.validate(self.changed(path,value))
    def test_missing_recovery_binding_cannot_pass(self):
        for path in ("t:Actions/t:Exec","t:Principals/t:Principal","t:Triggers/t:LogonTrigger","t:Settings"):
            with self.subTest(path=path),self.assertRaises(RuntimeError):self.validate(self.changed(path,remove=True))


class WorkerPauseRecovery(unittest.TestCase):
    def setUp(self):
        self.operation = {"startAt":"2026-10-26T21:30:00+08:00", "latestStartAt":"2026-10-26T21:40:00+08:00"}
        self.now = dt.datetime.fromisoformat("2026-10-26T21:35:00+08:00")
        self.task = {"state":"Ready", "enabled":True, "lastRun":"2026-10-26T21:30:01+08:00"}
    def test_pending_recovery_prevents_restore_after_task_finishes(self):
        self.assertFalse(PAUSE.restore_allowed(self.task,self.operation,1,self.now))
    def test_running_controller_prevents_restore(self):
        self.task["state"]="Running"
        self.assertFalse(PAUSE.restore_allowed(self.task,self.operation,0,self.now))
    def test_future_task_cannot_restore_pause_early(self):
        self.task["lastRun"]="1999-11-30T00:00:00+08:00"
        self.assertFalse(PAUSE.restore_allowed(self.task,self.operation,0,self.now))
        self.assertTrue(PAUSE.restore_allowed(self.task,self.operation,0,self.now+dt.timedelta(minutes=10)))
    def test_cancelled_task_can_restore_before_window(self):
        self.task.update(enabled=False,lastRun="1999-11-30T00:00:00+08:00")
        self.assertTrue(PAUSE.restore_allowed(self.task,self.operation,0,self.now))
    def test_completed_maintenance_can_restore(self):
        self.assertTrue(PAUSE.restore_allowed(self.task,self.operation,0,self.now))
    def test_restore_compares_identity_value_and_update_time(self):
        original={"id":12,"value":"true","updated":"2026-10-25 12:00:00.000000"}
        expected={"id":12,"value":"false","updated":"2026-10-26 21:30:00.000000"}
        statement=PAUSE.restore_sql(original,expected,expected.copy())
        self.assertIn("WHERE id=12",statement)
        self.assertIn("BINARY config_value='false' AND updated_at='2026-10-26 21:30:00.000000'",statement)
        for field,value in [("id",13),("value","true"),("updated","2026-10-26 21:31:00.000000")]:
            with self.subTest(field=field):
                changed={**expected,field:value}
                with self.assertRaises(RuntimeError):PAUSE.restore_sql(original,expected,changed)
        self.assertIsNone(PAUSE.restore_sql(original,expected,original.copy()))
    def test_original_disabled_value_stays_disabled(self):
        original={"id":12,"value":"false","updated":"2026-10-25 12:00:00.000000"}
        expected={"id":12,"value":"false","updated":"2026-10-26 21:30:00.000000"}
        self.assertIn("SET config_value='false'",PAUSE.restore_sql(original,expected,expected))
    def test_queued_and_unknown_tasks_cannot_restore(self):
        for state in ["Queued", "Unknown"]:
            self.task["state"]=state
            self.assertFalse(PAUSE.restore_allowed(self.task,self.operation,0,self.now))
    def test_interrupted_restore_recognizes_its_committed_version(self):
        original={"id":12,"value":"true","updated":"2026-10-25 12:00:00.000000"}
        paused={"id":12,"value":"false","updated":"2026-10-26 21:30:00.000000"}
        restored={"id":12,"value":"true","updated":"2026-10-26 21:31:00.000000"}
        self.assertIsNone(PAUSE.restore_sql(original,paused,restored,restored))
        self.assertIn("updated_at='2026-10-26 21:31:00.000000'",PAUSE.restore_sql(original,paused,paused,restored))
    def test_scheduler_bridge_does_not_emit_python_310_incompatible_fraction(self):
        self.assertNotIn("LastRunTime.ToString('o')",PATH.read_text(encoding="utf-8"))

    def test_invalid_original_value_is_refused(self):
        expected={"id":12,"value":"false","updated":"2026-10-26 21:30:00.000000"}
        with self.assertRaises(RuntimeError):PAUSE.restore_sql({**expected,"value":"unexpected"},expected,expected)


class ApplicationDatabaseTests(unittest.TestCase):
    def setUp(self):
        self.secret = "secret-only-in-child-env"
        self.config = {"docker": "docker.exe", "mysql": "mysql.exe", "mysqlDefaults": "readonly-backup.cnf", "python":"python.exe"}
        self.values = ["DB_USER=gying_app", "DB_PASSWORD=" + self.secret,
                       "DB_HOST=host.docker.internal", "DB_PORT=3307", "DB_NAME=gying"]
        self.labels = {"com.docker.compose.project": "gying-movie", "com.docker.compose.service": "backend"}
        self.identity = json.dumps({"account": "gying_app@%", "database": "gying"}).encode()
        self.grants = b"GRANT SELECT, INSERT, UPDATE, DELETE ON `gying`.* TO `gying_app`@`%`"

    def inspect(self, values=None, cid=None, image=None, running=True, labels=None):
        fields = {"id":cid or "a" * 64, "image":image or "sha256:" + "b" * 64,
                  "env":self.values if values is None else values, "running":running,
                  "labels":self.labels if labels is None else labels}
        return subprocess.CompletedProcess([], 0, json.dumps(fields).encode(), b"")

    def success(self, output):
        return subprocess.CompletedProcess([], 0, output, b"")

    @mock.patch.object(PAUSE.subprocess, "run")
    def test_preparation_verifies_app_identity_and_permissions_without_password_arguments(self, run):
        run.side_effect = [self.inspect(), self.success(self.identity), self.success(self.grants), self.success(b"19\tresource.hub.worker.enabled\ttrue\t2026-09-18 01:18:04")]
        database = PAUSE.ApplicationDatabase(self.config, require_running=True)
        database.verify()
        self.assertEqual(database.source["port"], 3307)
        self.assertNotIn(self.secret, json.dumps(database.source))
        for call in run.call_args_list:
            args = call.args[0]
            self.assertNotIn(self.secret, repr(args))
            if args[0] == "mysql.exe":
                self.assertIn("--no-defaults", args)
                self.assertIn("--protocol=TCP", args)
                self.assertIn("--host=127.0.0.1", args)
                self.assertIn("--port=3307", args)
                self.assertNotIn("readonly-backup.cnf", repr(args))
                self.assertEqual(call.kwargs["env"]["MYSQL_PWD"], self.secret)
        statements = [call.args[0][-1] for call in run.call_args_list[1:]]
        self.assertTrue(all(s.startswith(("SELECT ", "SHOW ")) for s in statements))

    @mock.patch.object(PAUSE.subprocess, "run")
    def test_read_only_account_cannot_prepare(self, run):
        run.side_effect = [self.inspect(), self.success(self.identity), self.success(b"GRANT SELECT, SHOW VIEW ON `gying`.* TO `gying_app`@`%`")]
        with self.assertRaisesRegex(PAUSE.DatabaseFailure, "SELECT/UPDATE required"):
            PAUSE.ApplicationDatabase(self.config).verify()
        self.assertEqual(run.call_count, 3)

    @mock.patch.object(PAUSE.subprocess, "run")
    def test_identity_mismatch_refused_before_permissions_or_writes(self, run):
        for identity in [{"account":"wrong@%", "database":"gying"}, {"account":"gying_app@%", "database":"other"}]:
            run.reset_mock()
            run.side_effect = [self.inspect(), self.success(json.dumps(identity).encode())]
            with self.assertRaisesRegex(PAUSE.DatabaseFailure, "identity mismatch"):
                PAUSE.ApplicationDatabase(self.config).verify()
            self.assertEqual(run.call_count, 2)

    @mock.patch.object(PAUSE.subprocess, "run")
    def test_recovery_credentials_require_same_container_image_and_user(self, run):
        run.return_value = self.inspect()
        source = PAUSE.ApplicationDatabase(self.config).source
        variants = [self.inspect(cid="c" * 64), self.inspect(image="sha256:" + "c" * 64),
                    self.inspect(values=["DB_USER=other", "DB_PASSWORD=" + self.secret])]
        for response in variants:
            run.return_value = response
            with self.assertRaisesRegex(PAUSE.DatabaseFailure, "identity changed"):
                PAUSE.ApplicationDatabase(self.config, expected=source)

    @mock.patch.object(PAUSE.subprocess, "run")
    def test_stopped_original_container_remains_valid_credential_source_for_recovery(self, run):
        run.return_value = self.inspect()
        source = PAUSE.ApplicationDatabase(self.config).source
        run.return_value = self.inspect(running=False)
        self.assertEqual(PAUSE.ApplicationDatabase(self.config, expected=source).source, source)
        with self.assertRaisesRegex(PAUSE.DatabaseFailure, "identity invalid"):
            PAUSE.ApplicationDatabase(self.config, expected=source, require_running=True)

    @mock.patch.object(PAUSE.subprocess, "run")
    def test_missing_password_root_wrong_schema_remote_host_and_non_backend_refused(self, run):
        variants = [self.inspect(values=["DB_USER=gying_app"]),
                    self.inspect(values=["DB_USER=root", "DB_PASSWORD=" + self.secret]),
                    self.inspect(values=["DB_USER=gying_app", "DB_PASSWORD=" + self.secret, "DB_NAME=other"]),
                    self.inspect(values=["DB_USER=gying_app", "DB_PASSWORD=" + self.secret, "DB_HOST=remote"]),
                    self.inspect(labels={"com.docker.compose.project":"other", "com.docker.compose.service":"backend"})]
        for response in variants:
            run.return_value = response
            with self.assertRaisesRegex(PAUSE.DatabaseFailure, "identity invalid"):
                PAUSE.ApplicationDatabase(self.config)

    @mock.patch.object(PAUSE.subprocess, "run")
    def test_mysql_errors_expose_only_stage_and_numeric_error(self, run):
        run.side_effect = [self.inspect(), subprocess.CompletedProcess([], 1, self.secret.encode(),
                           ("ERROR 1142 (42000): UPDATE denied to " + self.secret).encode())]
        database = PAUSE.ApplicationDatabase(self.config)
        with self.assertRaisesRegex(PAUSE.DatabaseFailure, "stage=pause mysql-error=1142") as raised:
            database.sql("UPDATE sys_config SET config_value='false'", "pause")
        self.assertNotIn(self.secret, str(raised.exception))
        self.assertNotIn("UPDATE denied", str(raised.exception))

    @mock.patch.object(PAUSE.subprocess, "run")
    def test_timeout_does_not_expose_credential_bearing_exception(self, run):
        run.side_effect = [self.inspect(), subprocess.TimeoutExpired(["mysql.exe"], 30, output=self.secret)]
        database = PAUSE.ApplicationDatabase(self.config)
        with self.assertRaisesRegex(PAUSE.DatabaseFailure, "stage=restore timeout") as raised:
            database.sql("SELECT 1", "restore")
        self.assertNotIn(self.secret, str(raised.exception))

    def test_grant_scopes_and_required_columns(self):
        accepted = ["GRANT SELECT, UPDATE ON `gying`.* TO `app`@`%`",
                    "GRANT ALL PRIVILEGES ON `gying`.`sys_config` TO `app`@`%`",
                    "GRANT SELECT ON `gying`.* TO `app`@`%`\nGRANT UPDATE (`config_value`, `updated_at`) ON `gying`.`sys_config` TO `app`@`%`"]
        rejected = ["GRANT SELECT ON `gying`.* TO `app`@`%`",
                    "GRANT SELECT, UPDATE ON `other`.* TO `app`@`%`",
                    "GRANT SELECT, UPDATE ON `gying`.`other_table` TO `app`@`%`",
                    "GRANT SELECT, UPDATE (`config_value`) ON `gying`.`sys_config` TO `app`@`%`",
                    "GRANT SELECT (`config_value`), UPDATE ON `gying`.`sys_config` TO `app`@`%`",
                    "GRANT SELECT, REFERENCES (`updated_at`) ON `gying`.`sys_config` TO `app`@`%`",
                    "GRANT ALL PRIVILEGES ON *.* TO `app`@`%`\nREVOKE UPDATE ON `gying`.* FROM `app`@`%`"]
        for grants in accepted:
            self.assertTrue(PAUSE.granted_worker_permissions(grants), grants)
        for grants in rejected:
            self.assertFalse(PAUSE.granted_worker_permissions(grants), grants)

    def test_checkpoint_must_have_verified_nonempty_mysql_archive(self):
        base = {"at":dt.datetime.now(dt.timezone.utc).isoformat(), "status":"complete", "verified":"SHA-256 and authenticated age decryption to null", "files":[]}
        for files in [None, [], [{"name":"other.age"}], ["mysql.sql.age"]]:
            with self.assertRaisesRegex(RuntimeError, "mysql.sql.age required"):
                PAUSE.verify_checkpoint({**base,"files":files})
        with tempfile.TemporaryDirectory() as directory:
            archive = pathlib.Path(directory) / "mysql.sql.age"
            archive.write_bytes(b"authenticated-fixture")
            digest = hashlib.sha256(archive.read_bytes()).hexdigest()
            checkpoint = {**base, "directory":directory, "files":[{"name":"mysql.sql.age", "sha256":digest}]}
            PAUSE.verify_checkpoint(checkpoint)
            archive.write_bytes(b"modified")
            with self.assertRaisesRegex(RuntimeError, "hash or path mismatch"):
                PAUSE.verify_checkpoint(checkpoint)



    def test_checkpoint_age_size_empty_archive_and_traversal_are_refused(self):
        reference=dt.datetime.now(dt.timezone.utc)
        with tempfile.TemporaryDirectory() as directory:
            archive=pathlib.Path(directory)/"mysql.sql.age"
            archive.write_bytes(b"authenticated-fixture")
            checkpoint={"at":reference.isoformat(),"status":"complete","verified":"SHA-256 and authenticated age decryption to null",
                        "directory":directory,"files":[{"name":"mysql.sql.age","size":archive.stat().st_size,
                                                          "sha256":hashlib.sha256(archive.read_bytes()).hexdigest()}]}
            PAUSE.verify_checkpoint(checkpoint,reference)
            for stamp in ((reference-dt.timedelta(hours=25)).isoformat(),(reference+dt.timedelta(minutes=6)).isoformat(),
                          reference.replace(tzinfo=None).isoformat(),"invalid"):
                with self.subTest(stamp=stamp),self.assertRaisesRegex(RuntimeError,"Fresh timezone-aware"):
                    PAUSE.verify_checkpoint({**checkpoint,"at":stamp},reference)
            for size in (0,True,"21",archive.stat().st_size+1):
                invalid={**checkpoint,"files":[{**checkpoint["files"][0],"size":size}]}
                with self.subTest(size=size),self.assertRaisesRegex(RuntimeError,"hash or path mismatch"):
                    PAUSE.verify_checkpoint(invalid,reference)
            invalid={**checkpoint,"files":[*checkpoint["files"],{**checkpoint["files"][0],"name":"../mysql.sql.age"}]}
            with self.assertRaisesRegex(RuntimeError,"hash or path mismatch"):PAUSE.verify_checkpoint(invalid,reference)
            archive.write_bytes(b"")
            empty={**checkpoint,"files":[{"name":"mysql.sql.age","size":0,"sha256":hashlib.sha256(b"").hexdigest()}]}
            with self.assertRaisesRegex(RuntimeError,"hash or path mismatch"):PAUSE.verify_checkpoint(empty,reference)


    def test_bound_application_connection_round_trip_prepare_pause_restore(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            config = root / "config.json"
            config.write_text("{}", encoding="utf-8")
            archive = root / "mysql.sql.age"
            archive.write_bytes(b"checkpoint")
            checkpoint = root / "checkpoint.json"
            checkpoint.write_text(json.dumps({"at":dt.datetime.now(dt.timezone.utc).isoformat(), "status":"complete", "verified":"SHA-256 and authenticated age decryption to null",
                                             "directory":directory, "files":[{"name":"mysql.sql.age", "sha256":hashlib.sha256(archive.read_bytes()).hexdigest()}]}), encoding="utf-8")
            registration = root / "registration.json"
            registration.write_text(json.dumps({"mode":"registered", "permit":"permit", "task":"GYing One-off Graceful Restart 29991026-2130",
                                                "startAt":"2999-10-26T21:30:00+08:00", "latestStartAt":"2999-10-26T21:40:00+08:00", "finishBefore":"2999-10-26T22:30:00+08:00"}), encoding="utf-8")
            original = {"id":19,"value":"true","updated":"2026-09-18 01:18:04.000000"}
            current = original.copy()
            stamps = iter(["2026-10-27 01:00:01.000000", "2026-10-27 01:00:02.000000"])
            statements = []
            bound = {"kind":"backend-container", "container":PAUSE.BACKEND, "id":"a"*64,
                     "image":"sha256:"+"b"*64, "user":"app", "host":"127.0.0.1", "port":3306, "database":"gying"}
            task_enabled = True
            operation = None
            invalid_guard = True
            def sql(statement, stage):
                statements.append((stage,statement))
                if statement.startswith("SELECT JSON_OBJECT"):
                    return json.dumps(current)
                if statement.startswith("SELECT DATE_FORMAT"):
                    return next(stamps)
                if statement.startswith("UPDATE "):
                    import re
                    current.update(value=re.search("SET config_value='([^']+)'",statement).group(1),
                                   updated=re.search("updated_at='([^']+)'",statement).group(1))
                    return "1"
                raise AssertionError(statement)
            def ps(statement):
                if "Export-ScheduledTask" in statement:
                    proof=guard_proof({**self.config,"host":"HOST"},PATH.resolve(),config.resolve(),operation.resolve(),read(operation)["guardTask"])
                    if invalid_guard:proof["name"]="Unbound guard"
                    return json.dumps(proof)
                return json.dumps({"state":"Ready" if task_enabled else "Disabled", "enabled":task_enabled,
                                   "lastRun":"1999-11-30T00:00:00+08:00"})
            def read(path):
                if pathlib.Path(path) == config:
                    return {**self.config,"host":"HOST","stateRoot":str(root/"state")}
                return json.loads(pathlib.Path(path).read_text(encoding="utf-8"))
            def save(path,state):
                pathlib.Path(path).write_text(json.dumps(state),encoding="utf-8")
            permit={"id":"fixture-permit","startAt":"2999-10-26T21:30:00+08:00","latestStartAt":"2999-10-26T21:40:00+08:00","finishBefore":"2999-10-26T22:30:00+08:00"}
            permit_result=(permit, *(dt.datetime.fromisoformat(permit[key]) for key in ("startAt","latestStartAt","finishBefore")))
            worker = {"read":read,"save":save,"ps":ps,"q":lambda value:"'"+str(value)+"'",
                      "oneoff_permit":mock.Mock(return_value=permit_result),"now":lambda:"2026-10-27T01:00:00+08:00",
                      "pending":lambda config:[],"lock":lambda config:__import__('contextlib').nullcontext(),
                      "Blocked":RuntimeError}
            application = mock.Mock()
            application.source = bound
            application.sql.side_effect = sql
            with mock.patch.object(PAUSE.runpy,"run_path",return_value=worker), \
                 mock.patch.object(PAUSE.ctypes,"windll",mock.Mock(),create=True), \
                 mock.patch.dict(PAUSE.os.environ,{"COMPUTERNAME":"HOST"}), \
                 mock.patch.object(PAUSE,"ApplicationDatabase",return_value=application) as factory, \
                 mock.patch("builtins.print") as printer:
                with mock.patch("sys.argv",["helper","--config",str(config),"--prepare","--registration",str(registration),"--checkpoint",str(checkpoint)]):
                    PAUSE.main()
                self.assertEqual(current,original)
                self.assertTrue(all(text.startswith("SELECT ") for stage,text in statements))
                operation = next((root/"resource-hub-quiesce").glob("*.json"))
                self.assertEqual(read(operation)["databaseSource"],bound)
                archive.write_bytes(b"tampered")
                with mock.patch("sys.argv",["helper","--config",str(config),"--pause","--operation",str(operation)]), self.assertRaisesRegex(RuntimeError,"hash or path mismatch"):
                    PAUSE.main()
                self.assertEqual(factory.call_count,1)
                archive.write_bytes(b"checkpoint")
                with mock.patch("sys.argv",["helper","--config",str(config),"--pause","--operation",str(operation)]), self.assertRaisesRegex(RuntimeError,"exact bound ASUS"):
                    PAUSE.main()
                self.assertEqual(current,original)
                self.assertEqual(read(operation)["phase"],"prepared")
                self.assertFalse(any(text.startswith("UPDATE ") for stage,text in statements))
                invalid_guard=False
                with mock.patch("sys.argv",["helper","--config",str(config),"--pause","--operation",str(operation)]):
                    PAUSE.main()
                paused = current.copy()
                self.assertEqual(paused["value"],"false")
                self.assertEqual(read(operation)["phase"],"paused")
                task_enabled = False
                with mock.patch("sys.argv",["helper","--config",str(config),"--watch","--operation",str(operation)]):
                    PAUSE.main()
                self.assertEqual(current["value"],"true")
                self.assertEqual(read(operation)["phase"],"restored")
                self.assertNotEqual(current["updated"],paused["updated"])
                self.assertEqual(application.verify.call_count,4)
                self.assertEqual(factory.call_args_list[1].kwargs,{"expected":bound,"require_running":True})
                self.assertEqual(factory.call_args_list[-1].kwargs,{"expected":bound})
                self.assertNotIn(self.secret,repr(printer.call_args_list))
                writes=[(stage,text) for stage,text in statements if text.startswith("UPDATE ")]
                self.assertEqual([stage for stage,text in writes],["pause","restore"])
                for stage,text in writes:
                    self.assertIn("WHERE id=19 AND config_key='resource.hub.worker.enabled'",text)
                    self.assertIn("AND BINARY config_value=",text)
                    self.assertIn("AND updated_at=",text)



class PreparedOperationRecoveryTests(unittest.TestCase):
    def setUp(self):
        self.temporary=tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root=pathlib.Path(self.temporary.name)
        self.config=self.root/"config.json"
        self.config.write_text("{}",encoding="utf-8")
        directory=self.root/"resource-hub-quiesce"
        directory.mkdir()
        self.operation=directory/"fixture.json"
        self.original={"id":19,"value":"true","updated":"2026-10-27 01:00:00.000000"}
        self.current=self.original.copy()
        self.bound={"kind":"backend-container","container":PAUSE.BACKEND,"id":"a"*64,
                    "image":"sha256:"+"b"*64,"user":"app","host":"127.0.0.1","port":3306,"database":"gying"}
        self.state={"phase":"prepared","host":"HOST","helperSha256":hashlib.sha256(PATH.read_bytes()).hexdigest(),
                    "databaseSource":self.bound,"targetTask":"Restart", "guardTask":"Guard", "original":self.original,
                    "startAt":"2999-10-26T21:30:00+08:00","latestStartAt":"2999-10-26T21:40:00+08:00"}
        self.save(self.operation,self.state)
        self.enabled=False
        self.pending=[]
        self.application=mock.Mock()
        self.application.sql.side_effect=lambda statement,stage:json.dumps(self.current) if statement.startswith("SELECT ") else self.fail("Prepared recovery must not write")
        self.worker={"read":self.read,"save":self.save,"ps":self.ps,"q":lambda value:"'"+str(value)+"'",
                     "now":lambda:"2026-10-27T01:00:00+08:00","pending":lambda config:self.pending,
                     "lock":lambda config:contextlib.nullcontext(),"Blocked":RuntimeError}
    def read(self,path):
        if pathlib.Path(path)==self.config:return {"host":"HOST","stateRoot":str(self.root/"state")}
        return json.loads(pathlib.Path(path).read_text(encoding="utf-8"))
    def save(self,path,state):pathlib.Path(path).write_text(json.dumps(state),encoding="utf-8")
    def ps(self,statement):
        return json.dumps({"state":"Ready" if self.enabled else "Disabled","enabled":self.enabled,
                           "lastRun":"1999-11-30T00:00:00+08:00"})
    def execute_watch(self,sleep=None):
        with mock.patch.object(PAUSE.runpy,"run_path",return_value=self.worker), \
             mock.patch.object(PAUSE.ctypes,"windll",mock.Mock(),create=True), \
             mock.patch.dict(PAUSE.os.environ,{"COMPUTERNAME":"HOST"}), \
             mock.patch.object(PAUSE,"ApplicationDatabase",return_value=self.application) as factory, \
             mock.patch.object(PAUSE.time,"sleep",side_effect=sleep), \
             mock.patch("builtins.print"), \
             mock.patch("sys.argv",["helper","--config",str(self.config),"--watch","--operation",str(self.operation)]):
            PAUSE.main()
            return factory
    def test_cancelled_prepared_operation_finishes_without_config_write(self):
        factory=self.execute_watch()
        restored=self.read(self.operation)
        self.assertEqual(restored["phase"],"restored")
        self.assertEqual(restored["completion"],"cancelled-before-pause")
        self.assertTrue(restored["originalUnchanged"])
        self.assertFalse(restored["workerChanged"])
        self.assertEqual(self.current,self.original)
        factory.assert_called_once_with({"host":"HOST","stateRoot":str(self.root/"state")},expected=self.bound)
    def test_concurrent_operator_change_is_preserved_on_pre_pause_cancellation(self):
        self.current={**self.original,"value":"false","updated":"2026-10-27 01:01:00.000000"}
        self.execute_watch()
        restored=self.read(self.operation)
        self.assertEqual(restored["phase"],"restored")
        self.assertFalse(restored["originalUnchanged"])
        self.assertEqual(restored["observedValue"],"false")
        self.assertEqual(self.current["updated"],"2026-10-27 01:01:00.000000")
    def test_active_future_restart_keeps_prepared_watch_alive_until_cancelled(self):
        self.enabled=True
        def sleep(seconds):
            self.application.verify.assert_not_called()
            self.enabled=False
        self.execute_watch(sleep)
        self.assertEqual(self.read(self.operation)["phase"],"restored")
    def test_pending_maintenance_recovery_prevents_prepared_finalization(self):
        self.pending=["unfinished-journal"]
        def sleep(seconds):
            self.application.verify.assert_not_called()
            self.assertEqual(self.read(self.operation)["phase"],"prepared")
            self.pending=[]
        self.execute_watch(sleep)
        self.assertEqual(self.read(self.operation)["phase"],"restored")
    def test_completed_old_helper_operation_is_noop_without_database_access(self):
        self.state.update(phase="restored",helperSha256="old-helper-version")
        self.state.pop("databaseSource")
        self.save(self.operation,self.state)
        factory=self.execute_watch()
        factory.assert_not_called()
        self.application.sql.assert_not_called()


if __name__ == "__main__":unittest.main()
