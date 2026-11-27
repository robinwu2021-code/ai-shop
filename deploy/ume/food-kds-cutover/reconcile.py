"""Copy and verify restaurant cutover rows while source and target writers are paused."""
import argparse
import re

import pymysql

KDS_KEYS = {
    "fnb_station": ("id",),
    "fnb_station_runtime": ("entity_no", "store_no", "station_no"),
    "fnb_kds_cursor": ("entity_no", "store_no"),
    "fnb_kds_event": ("entity_no", "store_no", "sequence_no"),
    "fnb_kds_subscription": ("subscription_no",),
    "fnb_kds_voice_command": ("command_no",),
}

TRADE_ADMISSION_KEYS = {
    "fnb_order_batch": ("batch_no",),
    "fnb_channel_order_source": ("source_no",),
    "fnb_channel_import_guard": ("guard_no",),
}
GROUPS = {"kds": KDS_KEYS, "trade-admission": TRADE_ADMISSION_KEYS}


def identifier(name):
    if not re.fullmatch(r"[A-Za-z0-9_]+", name):
        raise ValueError("Invalid SQL identifier")
    return f"`{name}`"


def admission_structure(cursor, table):
    cursor.execute(f"SHOW FULL COLUMNS FROM {table}")
    columns = {
        row[0]: (row[1], row[2], row[3], row[5], row[6])
        for row in cursor.fetchall()
    }
    cursor.execute(f"SHOW INDEX FROM {table}")
    indexes = {}
    for row in cursor.fetchall():
        if row[1] == 0:
            indexes.setdefault(row[2], []).append((row[3], row[4], row[7], row[10]))
    return columns, {tuple(sorted(parts)) for parts in indexes.values()}


def reconcile(db, source, target, mode, group="kds"):
    if source.casefold() == target.casefold():
        raise ValueError("Choose two distinct schemas")
    source, target = map(identifier, (source, target))
    results = {}
    try:
        with db.cursor() as cursor:
            cursor.execute("SET time_zone = '+00:00'")
            cursor.execute("SET SESSION sql_mode = 'STRICT_ALL_TABLES,NO_AUTO_VALUE_ON_ZERO'")
            cursor.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ")
            cursor.execute("START TRANSACTION WITH CONSISTENT SNAPSHOT" + (", READ ONLY" if mode == "verify" else ""))
            for table, keys in GROUPS[group].items():
                table = identifier(table)
                old, new = f"{source}.{table}", f"{target}.{table}"
                cursor.execute(f"SHOW COLUMNS FROM {old}")
                columns = [identifier(row[0]) for row in cursor.fetchall()]
                if group == "trade-admission":
                    original_columns, original_keys = admission_structure(cursor, old)
                    target_columns, target_keys = admission_structure(cursor, new)
                    if target_columns != original_columns:
                        raise ValueError(f"{table}: trade admission columns differ")
                    if not original_keys.issubset(target_keys):
                        raise ValueError(f"{table}: trade admission unique keys differ")
                keys = tuple(map(identifier, keys))
                if not set(keys).issubset(columns):
                    raise ValueError(f"{table}: source business key is missing")
                cursor.execute(f"SELECT COUNT(*) FROM {old}")
                expected, = cursor.fetchone()
                join = f" FROM {old} s LEFT JOIN {new} t ON " + " AND ".join(
                    f"s.{key}=t.{key} AND BINARY s.{key}=BINARY t.{key}" for key in keys
                )
                inserted = 0
                if mode == "apply":
                    cursor.execute(
                        f"INSERT INTO {new} ({','.join(columns)}) SELECT "
                        + ",".join(f"s.{column}" for column in columns)
                        + join + f" WHERE t.{keys[0]} IS NULL"
                    )
                    inserted = cursor.rowcount
                exact = " AND ".join(f"BINARY s.{c} <=> BINARY t.{c}" for c in columns)
                cursor.execute(
                    f"SELECT COUNT(*), COUNT(CASE WHEN t.{keys[0]} IS NOT NULL AND {exact} THEN 1 END)" + join
                )
                total, matched = cursor.fetchone()
                if total != expected or matched != expected:
                    raise ValueError(f"{table}: expected {expected} source rows, found {total} matches by key, {matched} exact")
                results[table] = (matched, inserted)
        db.commit()
        return results
    except Exception:
        db.rollback()
        raise


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("apply", "verify"))
    parser.add_argument("--group", choices=tuple(GROUPS), default="kds")
    for arg in ("config", "source", "target"):
        parser.add_argument("--" + arg, required=True)
    args = parser.parse_args()
    with pymysql.connect(read_default_file=args.config, charset="utf8mb4") as db:
        for table, (matched, inserted) in reconcile(db, args.source, args.target, args.mode, args.group).items():
            print(f"{table} matched={matched} inserted={inserted}")


if __name__ == "__main__":
    main()
