#!/usr/bin/env python3
"""Apply the ai-shop retention policy to closed gzip archives only."""
import argparse
from datetime import date, datetime
from pathlib import Path
import re
import stat
from zoneinfo import ZoneInfo

SHANGHAI = ZoneInfo("Asia/Shanghai")
MIB = 1 << 20
LOGS = (
    ("ai-shop/shop-app", r"(shop-app\.log)\.(\d{4}-\d{2}-\d{2})\.\d+\.gz", 14, 0),
    ("ai-shop/shop-job", r"(shop-job\.log)\.(\d{4}-\d{2}-\d{2})\.\d+\.gz", 14, 0),
    ("ai-shop/pay-svc", r"(pay-svc\.log)\.(\d{4}-\d{2}-\d{2})\.\d+\.gz", 30, 0),
    ("ai-shop/shop-app", r"(error\.log)\.(\d{4}-\d{2}-\d{2})\.\d+\.gz", 30, 0),
    ("ai-shop/shop-job", r"(error\.log)\.(\d{4}-\d{2}-\d{2})\.\d+\.gz", 30, 0),
    ("ai-shop/pay-svc", r"(error\.log)\.(\d{4}-\d{2}-\d{2})\.\d+\.gz", 30, 0),
    ("ai-shop/food-server", r"(food-server\.log)\.(\d{4}-\d{2}-\d{2})\.\d+\.gz", 14, 0),
    ("ai-shop/beauty-server", r"(beauty-server\.log)\.(\d{4}-\d{2}-\d{2})\.\d+\.gz", 14, 0),
    ("ai-shop/ops", r"([\w-]+\.log)-(\d{8})-\d{6}\.gz", 90, 300 * MIB),
    ("infra/mysql97", r"(error\.log)\.\d+\.gz", 30, 350 * MIB),
)


def candidates(root: Path, today: date):
    for relative, pattern, retention_days, cap in LOGS:
        directory = root / relative
        if directory.resolve() != directory or not directory.is_dir():
            continue
        keep = []
        for path in directory.iterdir():
            match = re.fullmatch(pattern, path.name)
            if not match:
                continue
            try:
                info = path.lstat()
            except FileNotFoundError:
                continue  # Logback may expire a closed archive at the same time.
            if not stat.S_ISREG(info.st_mode):
                continue
            try:
                if match.lastindex == 2:
                    stamp = datetime.strptime(match[2], "%Y%m%d" if relative == "ai-shop/ops" else "%Y-%m-%d").date()
                else:
                    stamp = datetime.fromtimestamp(info.st_mtime, SHANGHAI).date()
            except ValueError:
                continue
            if (today - stamp).days >= retention_days:
                yield path, info, "age"
            elif cap:
                keep.append((stamp, info.st_mtime, path, info))
        if cap:
            used = sum(item[3].st_size for item in keep)
            for _, _, path, info in sorted(keep):
                if used <= cap:
                    break
                used -= info.st_size
                yield path, info, "capacity"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=Path("/data/log"))
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args()
    count = size = 0
    for path, archived, reason in candidates(args.root.resolve(), datetime.now(SHANGHAI).date()):
        if args.apply:
            try:
                current = path.lstat()
                if (current.st_dev, current.st_ino) != (archived.st_dev, archived.st_ino):
                    continue
                path.unlink()
            except FileNotFoundError:
                continue
        print(f"event=log.archive.cleanup action={'delete' if args.apply else 'preview'} reason={reason} bytes={archived.st_size} file={path}")
        count += 1
        size += archived.st_size
    print(f"event=log.archive.cleanup_finished apply={str(args.apply).lower()} count={count} bytes={size}")


if __name__ == "__main__":
    main()
