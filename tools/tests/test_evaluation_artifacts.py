"""Snapshot planning and safely rendered human-review artifacts."""
import contextlib
import io
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
sys.path.insert(0,str(Path(__file__).resolve().parents[1] / "ai"))
import evaluate_agent as base
import comparable_evaluation as comparable
import export_catalog_snapshot as snapshot
import build_review_page as page


class ArtifactTests(unittest.TestCase):
    def test_snapshot_plan_does_not_read_database(self):
        with patch.object(snapshot.subprocess,"run",side_effect=AssertionError("db")),contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(0,snapshot.main([]))

    def test_snapshot_projection_excludes_user_data_and_resource_urls(self):
        self.assertIn("SET TRANSACTION READ ONLY",snapshot.CODE)
        self.assertIn("connection.rollback()",snapshot.CODE)
        self.assertNotIn("sys_user",snapshot.CODE)
        self.assertNotIn("r.url",snapshot.CODE)
        self.assertIn("douban_score",snapshot.CODE)

    def test_external_existing_snapshot_blocks_database(self):
        with tempfile.TemporaryDirectory() as directory,patch.object(snapshot.subprocess,"run",side_effect=AssertionError("db")):
            p=Path(directory)/"snapshot.json";p.write_text("{}")
            with contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(2,snapshot.main(["--run","--snapshot",str(p),"--manifest",str(Path(directory)/"new.json")]))

    def make_artifacts(self,root):
        dataset={"cases":[]}
        report={"datasetSha256":base.digest(dataset),"rows":[{"caseId":"fixture","repetition":1,"turn":1,"manualReviewRequired":True,"answerSha256":"a"*64}]}
        observations={"reportSha256":base.digest(report),"observations":[{"caseId":"fixture","repetition":1,"turn":1,"answerSha256":"a"*64,"answer":"</script><img src=x onerror=alert(1)>"}]}
        for name,value in (("d",dataset),("r",report),("o",observations)):
            comparable.write_new(root/(name+".json"),value)
        return ["--report",str(root/"r.json"),"--dataset",str(root/"d.json"),"--observations",str(root/"o.json"),"--output",str(root/"review.html")]

    def test_review_page_prevents_script_injection(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);args=self.make_artifacts(root)
            with contextlib.redirect_stdout(io.StringIO()): self.assertEqual(0,page.main(args))
            text=(root/"review.html").read_text(encoding="utf-8")
            self.assertNotIn("</script><img",text)
            self.assertIn("\\u003c/script",text)
            self.assertNotIn("innerHTML",text)

    def test_review_page_rejects_unbound_and_duplicate_observations(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);args=self.make_artifacts(root);p=root/"o.json";v=json.loads(p.read_text());v["observations"].append(v["observations"][0]);p.write_text(json.dumps(v))
            with contextlib.redirect_stdout(io.StringIO()): self.assertEqual(2,page.main(args))
            self.assertFalse((root/"review.html").exists())


if __name__=="__main__":unittest.main()
