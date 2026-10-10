#!/usr/bin/env python3
"""接口耗时日报：按接口聚合访问日志，把慢的排在前面（TDD-接口耗时与慢日志 AC6）。

    python3 scripts/slowlog-report.py /data/log/ai-shop/shop-app/shop-app.log
    ssh soukmind-tx 'cat /data/log/ai-shop/shop-app/shop-app.log' | python3 scripts/slowlog-report.py -
    python3 scripts/slowlog-report.py <日志> --slow-ms 1000 --top 20

读的是 ApiAccessLogFilter 写的那种行（`api m=GET p=/… s=200 ms=12 rid=…`）。

**这个脚本最要紧的一条是「解析不到就喊」。**
按正则读日志的脚本不会报「读不到」，只会读出空 —— 而空在监控里长得和
「系统很健康」一模一样。日志格式哪天变了（或 logger 名改了、或日志根本没开），
它会每天安静地输出「0 条慢请求」，而那正是最需要它说话的时候。
所以：**一行都没解析到 → 非零退出并说清楚可能的原因**，绝不打印一张好看的空表。
"""
import argparse
import re
import sys
import time
from collections import defaultdict

# `api m=GET p=/mp/goods/{goodsNo} s=200 ms=1234 rid=a1b2c3d4 [store=ST…]`
LINE = re.compile(
    r"\bapi m=(?P<m>[A-Z]+) p=(?P<p>\S+) s=(?P<s>\d{3}) ms=(?P<ms>\d+) rid=(?P<rid>\w+)")
# 行首的 ISO 时间戳：`2026-10-08T15:38:23.745+08:00  INFO …`
TS = re.compile(r"^(?P<ts>\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})")


def parse_since(expr):
    """`2h` / `90m` / `3d` → 绝对时刻；也收 `2026-10-08T15:00` 这种前缀。返回可直接比字符串的 ISO。"""
    if not expr:
        return None
    m = re.fullmatch(r"(\d+)([mhd])", expr)
    if m:
        n, unit = int(m.group(1)), m.group(2)
        delta = {"m": 60, "h": 3600, "d": 86400}[unit] * n
        return time.strftime("%Y-%m-%dT%H:%M:%S", time.localtime(time.time() - delta))
    return expr


def percentile(sorted_vals, q):
    """最近秩法。没有 numpy 依赖，几千条数据绰绰有余。"""
    if not sorted_vals:
        return 0
    k = max(0, min(len(sorted_vals) - 1, round(q * (len(sorted_vals) - 1))))
    return sorted_vals[k]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("log", help="日志文件路径，- 表示标准输入")
    ap.add_argument("--slow-ms", type=int, default=1000, help="判定「慢」的阈值，默认 1000")
    # SLO（用户 2026-10-08 定）：正常 ≤200ms；≥3s 当问题报。
    # 分档而不是只给一个阈值 —— 「1.2 秒」和「12 秒」是两回事，混在一个数里看不出来。
    ap.add_argument("--ok-ms", type=int, default=200, help="正常线，默认 200")
    ap.add_argument("--bad-ms", type=int, default=3000, help="问题线，默认 3000")
    ap.add_argument("--top", type=int, default=15, help="列前几个接口，默认 15")
    ap.add_argument("--since", default=None,
                    help="只看这之后的：2h / 90m / 3d，或 2026-10-08T15:00。不给=全文件")
    args = ap.parse_args()

    since = parse_since(args.since)
    src = sys.stdin if args.log == "-" else open(args.log, encoding="utf8", errors="replace")
    total_lines = 0
    # 被时间窗挡掉的 api 行。**单独数**：它和「格式没认出来」是两回事，
    # 混在一起会让「窗口给窄了」长得像「日志格式变了」。
    out_of_window = 0
    first_ts = last_ts = None
    by_api = defaultdict(list)
    errors = defaultdict(int)
    # **按 rid 去重**：一个慢请求会出现两行（api.access 一行 + api.slow 一行），
    # 不去重的话它在「次数」里被数两遍，而且慢的那些恰好全是被数两遍的那些 ——
    # 慢比例会被系统性高估。rid 就是为把这两行认成同一个请求而加的。
    seen = set()
    with src:
        for raw in src:
            total_lines += 1
            m = LINE.search(raw)
            if not m:
                continue
            t = TS.match(raw)
            ts = t.group("ts") if t else None
            if since and ts and ts < since:
                out_of_window += 1
                continue
            if m["rid"] in seen:
                continue
            # 记录真实覆盖区间 —— 报告必须自报「这是哪段时间的数」，
            # 否则一个聚合数分不清是「现在慢」还是「今天早些时候慢过」：
            # 2026-10-08 修完 /mp/community（79s→6.7s）之后，不带时间窗的报告
            # 仍然显示 p50=75542ms，因为修复前的两条还在同一个文件里。
            if ts:
                first_ts = ts if first_ts is None or ts < first_ts else first_ts
                last_ts = ts if last_ts is None or ts > last_ts else last_ts
            seen.add(m["rid"])
            key = f"{m['m']} {m['p']}"
            by_api[key].append(int(m["ms"]))
            if m["s"][0] in "45":
                errors[key] += 1

    parsed = sum(len(v) for v in by_api.values())

    # ── 前置断言：读到了东西吗 ────────────────────────────────────
    # 这一条平时永远绿，只在「格式变了 / 日志没开 / 文件给错了」那天值钱，
    # 而那天没有它，输出就是一张「0 条慢请求」的健康报表。
    if parsed == 0 and out_of_window > 0:
        print(f"✗ 读了 {total_lines} 行，认出 {out_of_window} 条访问日志，"
              f"但**全部早于 --since {args.since}**。", file=sys.stderr)
        print("  不是格式问题 —— 把窗口放宽，或确认这段时间真的没有请求。", file=sys.stderr)
        return 3
    if parsed == 0:
        print(f"✗ 读了 {total_lines} 行，**一条访问日志都没解析到**。", file=sys.stderr)
        print("  可能的原因，按概率排：", file=sys.stderr)
        print("   1. shop.obs.access-log 关着（那就只有慢日志，查不出总量）", file=sys.stderr)
        print("   2. ApiAccessLogFilter 的行格式变了，而本脚本的正则没跟上", file=sys.stderr)
        print("   3. 文件给错了 / 这段时间真的一个请求都没有", file=sys.stderr)
        print("  判据：日志里 grep 一下 ' api m=' —— 有行而本脚本读不到，就是第 2 种。", file=sys.stderr)
        return 2

    slow = {k: [x for x in v if x >= args.slow_ms] for k, v in by_api.items()}
    slow_total = sum(len(v) for v in slow.values())

    allv = sorted(v for vs in by_api.values() for v in vs)
    n_ok = sum(1 for v in allv if v <= args.ok_ms)
    n_bad = sum(1 for v in allv if v >= args.bad_ms)
    span = f"{first_ts} ~ {last_ts}" if first_ts else "（行首没有时间戳）"
    print(f"读入 {total_lines} 行，解析到 {parsed} 个请求（按 rid 去重后）")
    # **区间永远打出来。** 不打的话，一个聚合数读不出它覆盖了多久 ——
    # 而「修完之后旧数据还在文件里」会让报告长期显示一个已经不存在的问题。
    print(f"  覆盖时段：{span}"
          + (f"（--since {args.since}，窗口外还有 {out_of_window} 条）" if since else "（全文件）"))
    print(f"  正常 ≤{args.ok_ms}ms：{n_ok}（{n_ok * 100.0 / parsed:.1f}%）   "
          f"整体 p95={percentile(allv, 0.95)}ms  p99={percentile(allv, 0.99)}ms  max={allv[-1]}ms")
    print(f"  慢  ≥{args.slow_ms}ms：{slow_total}（{slow_total * 100.0 / parsed:.1f}%）")
    status = "✗ 有问题" if n_bad else "✓"
    print(f"  {status} ≥{args.bad_ms}ms：{n_bad}" + ("   ← 这些要查" if n_bad else ""))
    # 没有一条低于阈值 = 多半只拿到了慢日志，分母是假的。
    # 不说这一句的话，「慢请求占比 100%」看起来像系统全面崩溃，其实只是没开全量行。
    if slow_total == parsed:
        print("  ⚠ 没有任何低于阈值的请求 —— 多半 shop.obs.access-log 是关的，"
              "这里的占比没有分母，只能看绝对条数。")
    print()
    print(f"{'接口':<46}{'次数':>6}{'慢':>5}{'问题':>5}{'p50':>7}{'p95':>7}{'max':>8}{'4xx/5xx':>9}")
    print("-" * 93)

    # 按「慢的条数」排，其次按 p95 —— 要先看见的是「哪个接口在拖」，不是「哪个调用多」
    def bad_count(vals):
        return sum(1 for x in vals if x >= args.bad_ms)

    # 先按「≥问题线的条数」排，再按慢的条数 —— 要先看见的是「哪个接口在拖」，
    # 不是「哪个调用最多」。一个被调一万次的 50ms 接口排在前面没有意义。
    rows = sorted(by_api.items(),
                  key=lambda kv: (bad_count(kv[1]), len(slow[kv[0]]),
                                  percentile(sorted(kv[1]), 0.95)),
                  reverse=True)
    for key, vals in rows[:args.top]:
        v = sorted(vals)
        print(f"{key[:45]:<46}{len(v):>6}{len(slow[key]):>5}{bad_count(v):>5}"
              f"{percentile(v, 0.5):>7}{percentile(v, 0.95):>7}{v[-1]:>8}"
              f"{errors.get(key, 0):>9}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
