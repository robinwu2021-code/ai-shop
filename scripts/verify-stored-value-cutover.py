#!/usr/bin/env python3
"""Compare two quiesced MySQL stored-value ledgers using read-only snapshots."""
import argparse
import hashlib
from pathlib import Path
import subprocess
import tempfile

SQL = Path(__file__).resolve().parent / "sql"
START = "START TRANSACTION WITH CONSISTENT SNAPSHOT, READ ONLY;"


def queries(filename):
    script = (SQL / filename).read_text()
    _, start, body = script.partition(START)
    body, commit, suffix = body.rpartition("COMMIT;")
    if not start or not commit or suffix.strip():
        raise ValueError(f"Unexpected transaction boundaries in {filename}")
    return body


def inspect(client, config, database, tenant):
    # The control rows each have one column. Every business row has multiple columns.
    sql = f"""
        SET @tenant=CONVERT(X'{tenant.encode().hex()}' USING utf8mb4);
        SET SESSION time_zone='+00:00';
        SET SESSION TRANSACTION ISOLATION LEVEL REPEATABLE READ;
        {START}
        SELECT CONCAT(@@server_uuid, '/', DATABASE());
        SELECT COUNT(*) FROM sv_account WHERE tenant_no=CAST(@tenant AS BINARY) AND deleted=0;
        SELECT 'integrity';
        {queries('stored-value-cutover-balance.sql')}
        SELECT 'evidence';
        {queries('stored-value-cutover-evidence.sql')}
        COMMIT;
    """
    args = [client, f"--defaults-file={config}", "--batch", "--skip-column-names",
            "--default-character-set=utf8mb4", "--database", database]
    with tempfile.TemporaryFile() as output:
        subprocess.run(args, input=sql.encode(), stdout=output, check=True)
        output.seek(0)
        identity = output.readline().rstrip(b"\n")
        accounts = int(output.readline())
        if output.readline() != b"integrity\n":
            raise ValueError("Missing integrity result")
        differences = 0
        for row in output:
            if row == b"evidence\n":
                break
            differences += 1
        else:
            raise ValueError("Incomplete integrity result")
        digest = hashlib.sha256()
        for chunk in iter(lambda: output.read(1024 * 1024), b""):
            digest.update(chunk)
    return identity, accounts, differences, digest.digest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mysql", default="mysql")
    parser.add_argument("--tenant", default="MAIN")
    for side in ("source", "target"):
        parser.add_argument(f"--{side}-config", required=True, type=Path, help="MySQL client option file")
        parser.add_argument(f"--{side}-db", required=True)
    args = parser.parse_args()
    src, dst = [
        inspect(args.mysql, config, db, args.tenant)
        for config, db in ((args.source_config, args.source_db), (args.target_config, args.target_db))
    ]
    print(f"Accounts: source={src[1]}, target={dst[1]}")
    print(f"Ledger discrepancies: source={src[2]}, target={dst[2]}")
    if src[0] == dst[0]:
        parser.exit(1, "Both connections point to the same database.\n")
    if not src[1] or not dst[1]:
        parser.exit(1, "An empty account snapshot cannot establish cutover readiness.\n")
    if src[2] or dst[2] or src[3] != dst[3]:
        parser.exit(1, "Cutover evidence differs or the ledger is unbalanced. Inspect the read-only SQL results.\n")
    print("Account snapshots and ledger evidence match.")


if __name__ == "__main__":
    main()
