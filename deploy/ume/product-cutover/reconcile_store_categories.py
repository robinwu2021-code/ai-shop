#!/usr/bin/env python3
"""Compare or import frozen merchant shelves into the shared trade category table."""
import argparse
import hashlib
import re
import uuid

import pymysql


SOURCE_COLUMNS = ("tenant_no", "entity_no", "store_no", "category_no", "display_name", "sort", "enabled",
                  "deleted", "version", "created_at", "created_by", "updated_at", "updated_by")
TARGET_COLUMNS = ("tenant_no", "entity_no", "store_no", "platform_category_no", "name", "sort_no", "enabled",
                  "deleted", "version", "created_at", "created_by", "updated_at", "updated_by", "category_no")


def columns(names):
    return ", ".join(f"`{name}`" for name in names)


def shelf_no(store, category):
    # Java UUID.nameUUIDFromBytes uses MD5 over the supplied bytes, without a namespace prefix.
    digest = hashlib.md5((store + "\0" + category).encode("utf-8"), usedforsecurity=False).digest()
    return uuid.UUID(bytes=digest, version=3).hex


def reconcile(db, source, trade, mode):
    if not all(re.fullmatch(r"[a-zA-Z0-9_]+", name) for name in (source, trade)) or source.lower() == trade.lower():
        raise ValueError("Expected two distinct schema names")
    target = f"`{trade}`.`prd_store_category`"
    with db.cursor() as cursor:
        cursor.execute("SET time_zone='+00:00'")
        cursor.execute("SET SESSION sql_mode='STRICT_ALL_TABLES'")
        cursor.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ")
        cursor.execute("START TRANSACTION WITH CONSISTENT SNAPSHOT" + ("" if mode == "apply" else ", READ ONLY"))
        cursor.execute(f"SELECT {columns(SOURCE_COLUMNS)} FROM `{source}`.`mch_store_category` ORDER BY id")
        source_rows = cursor.fetchall()
        missing = []
        for row in source_rows:
            tenant, entity, store, category = row[:4]
            if (tenant != "MAIN" or not entity or len(entity) > 32 or not store or len(store) > 32
                    or not category or len(category) > 64):
                raise ValueError("Source shelf has an unsupported tenant or invalid business key")
            key = shelf_no(store, category)
            expected = (*row, key)
            cursor.execute(f"""
                SELECT {columns(TARGET_COLUMNS)}, catalog_no, inv_managed
                FROM {target} WHERE category_no=%s OR
                    (store_no=%s AND platform_category_no=%s AND (catalog_no IS NULL OR catalog_no=''))
                """, (key, store, category))
            found = cursor.fetchall()
            if not found:
                missing.append(expected)
            elif (len(found) != 1 or tuple(found[0][:-2]) != expected
                  or found[0][-2] not in (None, "") or found[0][-1] is not None):
                raise ValueError(f"Target shelf conflicts with source (row key {key})")

        if mode == "verify" and missing:
            raise ValueError(f"{len(missing)} source shelves missing")
        if mode == "apply" and missing:
            params = ", ".join(["%s"] * len(TARGET_COLUMNS))
            cursor.executemany(f"INSERT INTO {target} ({columns(TARGET_COLUMNS)}) VALUES ({params})", missing)
            if cursor.rowcount != len(missing):
                raise ValueError("Unexpected number of inserted shelves")
        return dict(source=len(source_rows), matched=len(source_rows) - len(missing),
                    missing=len(missing), inserted=len(missing) if mode == "apply" else 0)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", nargs="?", default="check", choices=("check", "apply", "verify"))
    for arg in ("config", "source", "trade"):
        parser.add_argument("--" + arg, required=True)
    args = parser.parse_args()
    with pymysql.connect(read_default_file=args.config, charset="utf8mb4") as db:
        try:
            result = reconcile(db, args.source, args.trade, args.mode)
            db.commit()
        except (ValueError, pymysql.MySQLError) as error:
            db.rollback()
            parser.exit(1, f"Shelf reconciliation failed: {error}\n")
    print(result)


if __name__ == "__main__":
    main()
