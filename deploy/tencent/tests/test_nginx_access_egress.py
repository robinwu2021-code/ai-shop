from pathlib import Path
import re
import subprocess
import unittest


DEPLOY = Path(__file__).resolve().parents[1]


class NginxAccessEgressTest(unittest.TestCase):
    def test_traffic_accounting_accepts_safe_and_legacy_formats(self):
        script = (DEPLOY / "logwatch.sh").read_text()
        parser = re.search(r"""awk -v tag="\$tag" '([^']+)' "\$f" """.rstrip(), script)
        self.assertIsNotNone(parser)
        samples = (
            "[25/Nov/2026:11:03:24 +0800] bytes=146 method=GET route=biz status=200 "
            "elapsed=0.010 request_id=abcd\n"
            '192.0.2.1 - - [25/Nov/2026:11:03:29 +0800] "GET / HTTP/1.1" 200 245 "-" "-"\n'
        )
        output = subprocess.check_output(["awk", "-v", "tag=IMG", parser[1]], input=samples, text=True)
        self.assertEqual(output.splitlines(), ["IMG 25/Nov/2026:11:03 146", "IMG 25/Nov/2026:11:03 245"])

    def test_file_format_only_emits_allowed_fields(self):
        source = (DEPLOY / "nginx/ai-shop-access-log.conf").read_text()
        fields = set(re.findall(r"\$[a-z_]+", source.split("log_format ai_shop_safe", 1)[1]))
        self.assertEqual(fields, {"$time_local", "$body_bytes_sent", "$ai_shop_method", "$ai_shop_route",
                                  "$status", "$request_time", "$request_id"})


if __name__ == "__main__":
    unittest.main()
