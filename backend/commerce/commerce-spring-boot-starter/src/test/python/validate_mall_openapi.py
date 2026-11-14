"""Validate fresh MallOpenApiResponseTest MVC output against published OpenAPI 3.1.
Run after Maven: python3 <this file>. Requires jsonschema>=4 with Draft 2020-12.
"""
import json
from pathlib import Path
from jsonschema import Draft202012Validator, FormatChecker

repo = Path(__file__).resolve().parents[6]
module = Path(__file__).resolve().parents[3]
spec = json.loads((repo / "docs/api/commerce-mall-openapi.json").read_text())
fixtures = json.loads((module / "target/mall-openapi-responses.json").read_text())
for component in spec["components"]["schemas"].values():
    Draft202012Validator.check_schema(component)
failures = []
for name, instance in fixtures.items():
    schema = {"$ref": "#/components/schemas/" + name, "components": spec["components"]}
    validator = Draft202012Validator(schema, format_checker=FormatChecker())
    failures.extend(f"{name}.{'.'.join(map(str, error.path))}: {error.message}" for error in validator.iter_errors(instance))
# Money remains constrained: null fixes must never admit JSON-number or fractional minor units.
money = {"$ref": "#/components/schemas/Money", "components": spec["components"]}
validator = Draft202012Validator(money)
for invalid in [{"currency": "CNY", "minor": 9007199254740993}, {"currency": "CNY", "minor": "1.5"}]:
    assert not validator.is_valid(invalid), invalid
if failures:
    raise AssertionError("\n".join(failures))
print(f"OpenAPI 3.1: {len(fixtures)} actual MVC response trees valid; pending payment, digital address, refund review, draft version nulls and exact money checked")
