#!/usr/bin/env python3
"""把 `b-app/.env.local` 的 key 直接写进离线工程的 Info.plist，并**逐条回读断言**。

## 为什么不能只靠 DCloud 的 `uniapp_module_config.rb`

它有一处短路 bug（5.26 仍在）：

```ruby
%i[appid appkey appsecret].each do |key|
  value = required_value(cfg, key, "push_getui.#{key}", strict)
  changed ||= set_nested_value(plist, ['getui', key.to_s], value) if present?(value)
end
```

`changed ||= expr` 在 `changed` 已经为真时**不求值 expr**。写完 `appid` 之后
`changed` 变 true，`appkey` 与 `appsecret` 一次都没写进去 —— 两者留空。

后果不报错：App 照常启动、个推 SDK 照常初始化、cid 也拿得到，
**只是推送永远发不出去**。2026-09-28 打第 5 版时被产物体检抓到。

`dcloud_appkey` 它根本不管（不在它的处理范围里），也在这里补。

## 用法

    python3 b-app/offline/ios/sync-info-plist.py <离线工程目录>

**每次 `pod install` 之后都要跑**：pod install 会重写 Info.plist 的模块相关项。
幂等，可以重复跑。
"""
import plistlib
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
ENV_FILE = ROOT / "b-app" / ".env.local"

# 与 gen-uniapp-config.py 同一套读法（带 `=` 的字面量），理由见那里
AMAP_PREFIX = "AMAP_KEY_IOS="
DCLOUD_PREFIX = "DCLOUD_APPKEY_IOS="
GETUI_APPID_PREFIX = "GETUI_APPID="
GETUI_APPKEY_PREFIX = "GETUI_APPKEY="
GETUI_APPSECRET_PREFIX = "GETUI_APPSECRET="


def read_env() -> dict[str, str]:
    if not ENV_FILE.exists():
        sys.exit(f"✗ 找不到 {ENV_FILE}")
    out: dict[str, str] = {}
    for line in ENV_FILE.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        name, _, value = line.partition("=")
        out[name.strip() + "="] = value.strip().strip('"').strip("'")
    return out


def push_enabled(project: Path) -> bool:
    """个推模块开没开 —— 判据取 Podfile，与 gen-uniapp-config.py 同一个真源。"""
    block = re.search(r"uniapp_subspecs\s*=\s*\[(.*?)^\]",
                      (project / "Podfile").read_text(encoding="utf-8"), re.S | re.M)
    for line in block.group(1).splitlines():
        s = line.strip()
        if s and not s.startswith("#") and s.startswith("'Push-Getui'"):
            return True
    return False


def main() -> None:
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    project = Path(sys.argv[1]).expanduser().resolve()
    plist_path = next(project.glob("*/[A-Za-z]*-Info.plist"), None)
    if plist_path is None:
        sys.exit(f"✗ 在 {project} 下找不到 *-Info.plist")

    env = read_env()
    d = plistlib.loads(plist_path.read_bytes())

    want: dict[tuple[str, ...], str] = {
        ("dcloud_appkey",): env.get(DCLOUD_PREFIX, ""),
        ("amap", "appkey"): env.get(AMAP_PREFIX, ""),
    }
    if push_enabled(project):
        want[("getui", "appid")] = env.get(GETUI_APPID_PREFIX, "")
        want[("getui", "appkey")] = env.get(GETUI_APPKEY_PREFIX, "")
        want[("getui", "appsecret")] = env.get(GETUI_APPSECRET_PREFIX, "")

    missing = [".".join(k) for k, v in want.items() if not v]
    if missing:
        sys.exit("✗ .env.local 里这些还是空的：\n"
                 + "".join(f"    {m}\n" for m in missing)
                 + "  少了不报错，只是那一项静默不工作。")

    for path, value in want.items():
        node = d
        for seg in path[:-1]:
            node = node.setdefault(seg, {})
        node[path[-1]] = value

    plist_path.write_bytes(plistlib.dumps(d))

    # 回读断言。**写了不等于写对了** —— 上面那个 DCloud 的短路 bug 就是
    # 「跑完没报错，而值是空的」，只有回读才看得见。
    back = plistlib.loads(plist_path.read_bytes())
    for path, value in want.items():
        node = back
        for seg in path:
            node = (node or {}).get(seg) if isinstance(node, dict) else None
        if node != value:
            sys.exit(f"✗ 回读失败：{'.'.join(path)} 没写进去")
    print(f"✓ Info.plist 已同步（{plist_path.name}）")
    for path, value in want.items():
        print(f"    {'.'.join(path):22s} {len(value)} 位")
    print("  （只打长度不打值）")


if __name__ == "__main__":
    main()
