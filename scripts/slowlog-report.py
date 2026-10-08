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
from collections import defaultdict

# `api m=GET p=/mp/goods/{goodsNo} s=200 ms=1234 rid=a1b2c3d4 [store=ST…]`
LINE = re.compile(
    r"\bapi m=(?P<m>[A-Z]+) p=(?P<p>\S+) s=(?P<s>\d{3}) ms=(?P<ms>\d+) rid=(?P<rid>\w+)")


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
    ap.add_argument("--top", type=int, default=15, help="列前几个接口，默认 15")
    args = ap.parse_args()

    src = sys.stdin if args.log == "-" else open(args.log, encoding="utf8", errors="replace")
    total_lines = 0
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
            if m["rid"] in seen:
                continue
            seen.add(m["rid"])
            key = f"{m['m']} {m['p']}"
            by_api[key].append(int(m["ms"]))
            if m["s"][0] in "45":
                errors[key] += 1

    parsed = sum(len(v) for v in by_api.values())

    # ── 前置断言：读到了东西吗 ────────────────────────────────────
    # 这一条平时永远绿，只在「格式变了 / 日志没开 / 文件给错了」那天值钱，
    # 而那天没有它，输出就是一张「0 条慢请求」的健康报表。
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

    print(f"读入 {total_lines} 行，解析到 {parsed} 个请求（按 rid 去重后）；"
          f"其中 ≥{args.slow_ms}ms 的 {slow_total} 个"
          f"（{slow_total * 100.0 / parsed:.1f}%）")
    # 没有一条低于阈值 = 多半只拿到了慢日志，分母是假的。
    # 不说这一句的话，「慢请求占比 100%」看起来像系统全面崩溃，其实只是没开全量行。
    if slow_total == parsed:
        print("  ⚠ 没有任何低于阈值的请求 —— 多半 shop.obs.access-log 是关的，"
              "这里的占比没有分母，只能看绝对条数。")
    print()
    print(f"{'接口':<46}{'次数':>6}{'慢':>5}{'p50':>7}{'p95':>7}{'max':>8}{'4xx/5xx':>9}")
    print("-" * 88)

    # 按「慢的条数」排，其次按 p95 —— 要先看见的是「哪个接口在拖」，不是「哪个调用多」
    rows = sorted(by_api.items(),
                  key=lambda kv: (len(slow[kv[0]]), percentile(sorted(kv[1]), 0.95)),
                  reverse=True)
    for key, vals in rows[:args.top]:
        v = sorted(vals)
        print(f"{key[:45]:<46}{len(v):>6}{len(slow[key]):>5}"
              f"{percentile(v, 0.5):>7}{percentile(v, 0.95):>7}{v[-1]:>8}"
              f"{errors.get(key, 0):>9}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
