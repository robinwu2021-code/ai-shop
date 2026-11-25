import gzip
import os
from datetime import datetime, timedelta
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from zoneinfo import ZoneInfo


SCRIPT = Path(__file__).resolve().parents[1] / "clean-log-archives.py"


class ArchiveCleanupTest(unittest.TestCase):
    def test_preview_and_apply(self):
        now = datetime.now(ZoneInfo("Asia/Shanghai"))
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            app = root / "ai-shop/shop-app"
            pay = root / "ai-shop/pay-svc"
            ops = root / "ai-shop/ops"
            food = root / "ai-shop/food-server"
            beauty = root / "ai-shop/beauty-server"
            mysql = root / "infra/mysql97"
            incident = root / "ai-shop/incident"
            elapsed = now - timedelta(days=32)
            expired = [
                app / f"shop-app.log.{elapsed.date()}.0.gz",
                pay / f"pay-svc.log.{elapsed.date()}.2.gz",
                mysql / "error.log.2.gz",
                food / f"food-server.log.{elapsed.date()}.2.gz",
                beauty / f"beauty-server.log.{elapsed.date()}.0.gz",
                ops / f"backup.log-{(now-timedelta(days=95)):%Y%m%d}-043000.gz",
            ]
            protected = [
                app / "shop-app.log",
                app / f"shop-app.log.{now.date()}.0.gz",
                pay / f"pay-svc.log.{elapsed.date()}.0.gz.tmp",
                incident / f"pay-svc.log.{elapsed.date()}.1.gz",
            ]
            for file in expired + protected:
                file.parent.mkdir(parents=True, exist_ok=True)
                with gzip.open(file, "wb") as out:
                    out.write(b"fixture")
            os.utime(expired[2], (elapsed.timestamp(),) * 2)
            link = pay / f"pay-svc.log.{elapsed.date()}.3.gz"
            link.symlink_to(protected[0])
            over = ops / f"backup.log-{(now-timedelta(days=1)):%Y%m%d}-013000.gz"
            with over.open("wb") as out:
                out.truncate(301 << 20)
            command = [sys.executable, str(SCRIPT), "--root", tmp]
            preview = subprocess.check_output(command, text=True)
            self.assertIn("count=7", preview)
            self.assertIn("reason=capacity", preview)
            self.assertTrue(all(f.exists() for f in expired + protected + [link, over]))
            apply = subprocess.check_output(command + ["--apply"], text=True)
            self.assertIn("count=7", apply)
            self.assertTrue(all(not f.exists() for f in expired + [over]))
            self.assertTrue(all(f.exists() for f in protected + [link]))


if __name__ == "__main__":
    unittest.main()
