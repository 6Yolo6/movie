"""Offline integrity checks for development v3 and its disclosed historical lineage."""
import copy
import json
import re
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools/ai"))
import comparable_evaluation as comparable
import evaluate_agent as base
import evaluate_website as website
import quality_review as quality

DIRECTORY = ROOT / "docs/evaluation/development-v3"


def read(path):
    return json.loads(path.read_text(encoding="utf-8-sig"))


def messages(data):
    for case in data["cases"]:
        for turn in case["turns"]:
            message = turn.get("request", {}).get("message", turn.get("message"))
            yield case["id"], re.sub(r"[\W_]+", "", message.casefold())


class DevelopmentV3Tests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.data = read(DIRECTORY / "cases.json")
        cls.manifest = read(DIRECTORY / "freeze.json")

    def test_dataset_and_rubrics_are_bound_to_new_freeze(self):
        comparable.validate_dataset(self.data)
        comparable.verify_freeze(self.data, self.manifest)
        changed = copy.deepcopy(self.data)
        changed["cases"][0]["turns"][0]["reviewRubric"]["hardConditions"][0] = "changed label"
        with self.assertRaises(base.EvaluationError):
            comparable.verify_freeze(changed, self.manifest)

    def test_development_cannot_claim_holdout_or_automatic_quality_labels(self):
        self.assertEqual("agent-authored-regression", self.data["provenance"])
        self.assertTrue(8 <= len(self.data["cases"]) <= 12)
        self.assertNotIn("authorshipAttestation", self.data)
        for case in self.data["cases"]:
            with self.subTest(case=case["id"]):
                self.assertEqual("development", case["split"])
                self.assertTrue(case["sourceGroup"].startswith("dev-v3-"))
                for turn in case["turns"]:
                    self.assertEqual({}, turn["expect"])
                    self.assertNotIn("scores", turn)
                    self.assertNotIn("relevantIds", turn)

    def test_each_turn_separates_hard_conditions_and_soft_preferences(self):
        covered = set()
        self.assertEqual(set(quality.FAILURE_CATEGORIES), set(self.data["failureTaxonomy"]))
        has_soft_preferences = has_history = False
        for case in self.data["cases"]:
            self.assertTrue(case["failureCategories"])
            self.assertEqual(len(case["failureCategories"]), len(set(case["failureCategories"])))
            self.assertLessEqual(set(case["failureCategories"]), set(quality.FAILURE_CATEGORIES))
            covered.update(case["failureCategories"])
            for index, turn in enumerate(case["turns"]):
                with self.subTest(case=case["id"], turn=index + 1):
                    rubric = turn["reviewRubric"]
                    self.assertEqual({"hardConditions", "softPreferences", "expectedBehavior", "usefulApproximationBoundary"}, set(rubric))
                    for field in ("hardConditions", "softPreferences", "expectedBehavior"):
                        self.assertIsInstance(rubric[field], list)
                        self.assertTrue(all(isinstance(value, str) and value.strip() for value in rubric[field]))
                    self.assertTrue(rubric["hardConditions"])
                    self.assertTrue(rubric["expectedBehavior"])
                    self.assertIsInstance(rubric["usefulApproximationBoundary"], str)
                    self.assertTrue(rubric["usefulApproximationBoundary"].strip())
                    self.assertEqual(index > 0, turn["useHistory"])
                    has_soft_preferences |= bool(rubric["softPreferences"])
                    has_history |= turn["useHistory"]
        self.assertEqual(set(quality.FAILURE_CATEGORIES), covered)
        self.assertTrue(has_soft_preferences)
        self.assertTrue(has_history)

    def test_historical_inputs_remain_unchanged_and_lineage_resolves(self):
        references = {}
        self.assertEqual({"human-holdout-v1", "development-v2"}, {x["dataset"] for x in self.data["sourceComparisons"]})
        for comparison in self.data["sourceComparisons"]:
            historical = read(ROOT / comparison["path"])
            self.assertEqual(comparison["canonicalSha256"], base.digest(historical))
            references[comparison["dataset"]] = {case[comparison["referenceKey"]] for case in historical["cases"]}
            if comparison["dataset"] == "human-holdout-v1":
                website.verify_freeze(historical, read(ROOT / "docs/evaluation/human-holdout-v1/freeze.json"))
        for case in self.data["cases"]:
            with self.subTest(case=case["id"]):
                self.assertTrue(case["lineageNote"].strip())
                self.assertTrue(case["relatedSourceGroups"])
                self.assertEqual(len(case["relatedSourceGroups"]), len(set(case["relatedSourceGroups"])))
                for reference in case["relatedSourceGroups"]:
                    name, group = reference.split(":", 1)
                    self.assertIn(name, references)
                    self.assertIn(group, references[name])

    def test_no_normalized_exact_question_reuse_from_historical_sets(self):
        historical = {}
        for comparison in self.data["sourceComparisons"]:
            for case_id, message in messages(read(ROOT / comparison["path"])):
                historical.setdefault(message, []).append(comparison["dataset"] + ":" + case_id)
        current = set()
        for case_id, message in messages(self.data):
            with self.subTest(case=case_id):
                self.assertNotIn(message, historical, "Exact historical question reuse must be disclosed as a variant in a separate version")
                self.assertNotIn(message, current)
                current.add(message)


if __name__ == "__main__":
    unittest.main()
