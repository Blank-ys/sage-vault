import json
import unittest
from pathlib import Path

from jsonschema import Draft202012Validator, FormatChecker


class ContractExamplesTest(unittest.TestCase):
    def test_empty_knowledge_base_example_uses_registered_events(self) -> None:
        root = Path(__file__).parents[1] / "java-python-rag" / "v1"
        example = json.loads((root / "examples" / "empty-knowledge-base.json").read_text(encoding="utf-8"))
        schemas = {
            path.stem.split(".")[0]: json.loads(path.read_text(encoding="utf-8"))
            for path in (root / "events").glob("*.schema.json")
        }

        self.assertEqual(["started", "refused"], [event["type"] for event in example["events"]])
        for event in example["events"]:
            schema = schemas[event["type"]]
            Draft202012Validator(schema, format_checker=FormatChecker()).validate(event)


if __name__ == "__main__":
    unittest.main()
