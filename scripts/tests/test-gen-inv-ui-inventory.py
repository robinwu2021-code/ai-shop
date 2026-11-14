"""Inventory documentation must read actual locale values, including quoted keys."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / "gen-inv-ui-inventory.py"
spec = importlib.util.spec_from_file_location("inventory_ui", SCRIPT)
inventory = importlib.util.module_from_spec(spec)
spec.loader.exec_module(inventory)


class LocaleParsingTest(unittest.TestCase):
    def parse(self, source):
        with tempfile.TemporaryDirectory() as directory:
            file = Path(directory) / "locale.ts"
            file.write_text(source, encoding="utf-8")
            return inventory.load_locale(file)

    def test_quoted_names_preserve_chinese(self):
        locale = self.parse('export default { "stockOverview": { "book": "账面结存" } };')
        self.assertEqual(inventory.get(locale, "stockOverview.book"), "账面结存")

    def test_urls_and_escaped_values_are_not_comments(self):
        values = {"notice": {"url": "https://shop.example/a//b", "title": '库存 "确认"', "path": "a\\b"}}
        locale = self.parse("export default " + json.dumps(values, ensure_ascii=False) + ";")
        for key, expected in values["notice"].items():
            self.assertEqual(inventory.get(locale, "notice." + key), expected)

    def test_invalid_catalog_cannot_silently_generate_empty_labels(self):
        with self.assertRaises(ValueError):
            self.parse('export const invalid = { "book": "库存" };')


if __name__ == "__main__":
    unittest.main()
