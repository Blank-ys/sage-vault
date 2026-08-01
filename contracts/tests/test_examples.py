import json
import unittest
from pathlib import Path

import yaml
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

    def test_stopped_generation_example_matches_schemas(self) -> None:
        root = Path(__file__).parents[1] / "java-python-rag" / "v1"
        openapi = yaml.safe_load((root / "openapi.yaml").read_text(encoding="utf-8"))
        schemas = openapi["components"]["schemas"]
        example = json.loads((root / "examples" / "stopped-generation.json").read_text(encoding="utf-8"))
        event_schemas = {
            path.stem.split(".")[0]: json.loads(path.read_text(encoding="utf-8"))
            for path in (root / "events").glob("*.schema.json")
        }

        Draft202012Validator(schemas["CancelAnswerCommand"], format_checker=FormatChecker()).validate(
            example["request"]
        )
        Draft202012Validator(schemas["CancelAnswerAck"], format_checker=FormatChecker()).validate(
            example["response"]
        )

        self.assertEqual(["started", "delta", "stopped"], [event["type"] for event in example["events"]])
        for event in example["events"]:
            Draft202012Validator(event_schemas[event["type"]], format_checker=FormatChecker()).validate(event)

    def test_cancel_endpoint_is_registered(self) -> None:
        root = Path(__file__).parents[1] / "java-python-rag" / "v1"
        openapi = yaml.safe_load((root / "openapi.yaml").read_text(encoding="utf-8"))
        self.assertIn("/internal/v1/answers/{generationId}/cancel", openapi["paths"])

    def test_indexing_command_example_matches_schema(self) -> None:
        root = Path(__file__).parents[1] / "java-python-rag" / "v1"
        openapi = yaml.safe_load((root / "openapi.yaml").read_text(encoding="utf-8"))
        schemas = openapi["components"]["schemas"]
        example = json.loads((root / "examples" / "indexing-command.json").read_text(encoding="utf-8"))
        Draft202012Validator(schemas["IndexingCommand"], format_checker=FormatChecker()).validate(example["request"])

    def test_indexing_callback_example_matches_schema(self) -> None:
        root = Path(__file__).parents[1] / "java-python-rag" / "v1"
        openapi = yaml.safe_load((root / "openapi.yaml").read_text(encoding="utf-8"))
        schemas = openapi["components"]["schemas"]
        example = json.loads((root / "examples" / "indexing-callback.json").read_text(encoding="utf-8"))
        Draft202012Validator(schemas["IndexingCallback"], format_checker=FormatChecker()).validate(example["request"])

    def test_cleanup_command_example_matches_schema(self) -> None:
        root = Path(__file__).parents[1] / "java-python-rag" / "v1"
        openapi = yaml.safe_load((root / "openapi.yaml").read_text(encoding="utf-8"))
        schemas = openapi["components"]["schemas"]
        example = json.loads((root / "examples" / "cleanup-command.json").read_text(encoding="utf-8"))
        Draft202012Validator(schemas["CleanupCommand"], format_checker=FormatChecker()).validate(example["request"])

    def test_cleanup_callback_example_matches_schema(self) -> None:
        root = Path(__file__).parents[1] / "java-python-rag" / "v1"
        openapi = yaml.safe_load((root / "openapi.yaml").read_text(encoding="utf-8"))
        schemas = openapi["components"]["schemas"]
        example = json.loads((root / "examples" / "cleanup-callback.json").read_text(encoding="utf-8"))
        Draft202012Validator(schemas["CleanupCallback"], format_checker=FormatChecker()).validate(example["request"])


if __name__ == "__main__":
    unittest.main()
