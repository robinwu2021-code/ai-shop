#!/usr/bin/env python3
"""Verify V17 against a disposable MySQL 9.7 instance; never connect to application databases."""
import argparse
from pathlib import Path
import subprocess
import tempfile
import time


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--mysql-home", default="/opt/homebrew/opt/mysql@9.7")
    args = parser.parse_args()
    binaries = Path(args.mysql_home) / "bin"
    version = subprocess.check_output([str(binaries / "mysqld"), "--no-defaults", "--version"], text=True)
    if "Ver 9.7." not in version:
        raise SystemExit("Expected MySQL 9.7, found: " + version.strip())
    migration = (Path(__file__).resolve().parents[1]
                 / "backend/pay/pay-domain/src/main/resources/db/pay/V17__pay_order_close_intent.sql").read_text()
    with tempfile.TemporaryDirectory(prefix="pay-close-v17-", dir="/private/tmp") as temporary:
        directory = Path(temporary)
        data = directory / "data"
        data.mkdir()
        socket = directory / "mysql.sock"
        log = directory / "mysql.log"
        subprocess.run([str(binaries / "mysqld"), "--no-defaults", "--initialize-insecure",
                        f"--datadir={data}", f"--log-error={log}"], check=True,
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        server = subprocess.Popen([str(binaries / "mysqld"), "--no-defaults", f"--datadir={data}",
                                   "--skip-networking", "--mysqlx=OFF", f"--socket={socket}",
                                   f"--pid-file={directory / 'mysql.pid'}", f"--log-error={log}"],
                                  stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        client = [str(binaries / "mysql"), "--no-defaults", "--protocol=SOCKET", f"--socket={socket}",
                  "-uroot", "--batch", "--skip-column-names"]
        try:
            for _ in range(120):
                ready = subprocess.run(client + ["-e", "SELECT 1"], capture_output=True, text=True)
                if ready.returncode == 0:
                    break
                if server.poll() is not None:
                    raise RuntimeError("Disposable server stopped: " + log.read_text())
                time.sleep(0.25)
            else:
                raise RuntimeError("Disposable server did not become ready")
            sql = """CREATE DATABASE pay_close_verify;
USE pay_close_verify;
CREATE TABLE pay_order (id BIGINT PRIMARY KEY, status VARCHAR(32) NOT NULL, version BIGINT NOT NULL);
INSERT INTO pay_order VALUES (1, 'AWAITING_PAYMENT', 7);
""" + migration + """
SELECT status,version,close_requested_at IS NULL FROM pay_order WHERE id=1;
UPDATE pay_order SET close_requested_at='2026-10-28 15:00:00.123' WHERE id=1;
SELECT DATE_FORMAT(close_requested_at,'%Y-%m-%d %H:%i:%s.%f') FROM pay_order WHERE id=1;
"""
            checked = subprocess.run(client, input=sql, capture_output=True, text=True)
            if checked.returncode:
                raise RuntimeError(checked.stderr)
            lines = checked.stdout.strip().splitlines()
            assert lines == ["AWAITING_PAYMENT\t7\t1", "2026-10-28 15:00:00.123000"], lines
            print("PASS MySQL 9.7: V17 preserves existing rows and stores nullable millisecond close intent")
        finally:
            server.terminate()
            try:
                server.wait(timeout=10)
            except subprocess.TimeoutExpired:
                server.kill()
                server.wait()


if __name__ == "__main__":
    main()
