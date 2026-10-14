#!/usr/bin/env python3
"""把 `b-app/.env.local` 里的第三方 key 灌进离线工程的 `uniapp_config.rb`。

**这是 iOS 侧的 `amap-key.gradle`** —— 同一个理由：接线逻辑留在仓库里，
离线工程（在仓库外）只留一份被生成的配置。工程重解压一次，配置跟着重生成，
而不是像 2026-08-22 那次那样，注入代码随 SDK 重解压一起没了、六天没人发现。

## 为什么不直接编辑离线工程里那份

`HBuilder-Hello/uniapp_config.rb` 是 DCloud 的**演示配置**：`amap.appkey`、
`getui.*`、`dcloud_appkey` 解压出来就**全都有值**，是 HelloH5 那个演示应用的。
手工改的话，改漏一个字段没有任何迹象 —— 打出来的包安静地用演示账号连高德和个推。
所以这里整份重写，不做增量修改。

## 为什么要自己断言

官方的 `scripts/uniapp_module_config.rb` 有个 `strict` 开关，但它**只 warn 不失败**
（`required_value` → `warn_missing`）。少一个 key 的代价是真机上定位报错误码 7，
而 pod install 的输出里只飘一行英文警告 —— 与「没有闸门」等价。
这里缺值一律非零退出。

用法：
    python3 b-app/offline/ios/gen-uniapp-config.py <离线工程目录>
    python3 b-app/offline/ios/gen-uniapp-config.py <离线工程目录> --check   # 只校验不写
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
ENV_FILE = ROOT / "b-app" / ".env.local"

# 读 `.env.local` 的键。**写成带 `=` 的字面量是有意的**：
# `packages/shared/tests/env-consumed.test.ts` 判「有没有人读这个变量」看的是
# 各语言里读环境变量的**实际长相**，不是名字出现过没有（它自己栽过这个跟头：
# verify-apk.sh 的报错文案把变量名算成了消费方）。解析 .env 的前缀正是它认的一种。
AMAP_PREFIX = "AMAP_KEY_IOS="
GETUI_APPID_PREFIX = "GETUI_APPID="
GETUI_APPKEY_PREFIX = "GETUI_APPKEY="
GETUI_APPSECRET_PREFIX = "GETUI_APPSECRET="
DCLOUD_PREFIX = "DCLOUD_APPKEY_IOS="

# subspec → 它要哪几个值。**判据挂在「这个模块开没开」上**，
# 而不是无条件全要：将来关掉个推时，不该还逼着填个推的三件套。
NEEDED_BY = {
    "Map-Gaode": [AMAP_PREFIX],
    "Geolocation-Gaode": [AMAP_PREFIX],
    "Push-Getui": [GETUI_APPID_PREFIX, GETUI_APPKEY_PREFIX, GETUI_APPSECRET_PREFIX],
}

# DCloud 的 appkey 与模块无关：**没有它 App 根本起不来**（启动即报「未找到 appkey」），
# 所以不挂在任何 subspec 上，恒为必填。按平台绑定，Android 那把在 iOS 上不认。
ALWAYS_NEEDED = [DCLOUD_PREFIX]


def read_env() -> dict[str, str]:
    """解析 `.env.local`。不存在就直接报错 —— 它是 gitignore 的，每台机器要自己建。"""
    if not ENV_FILE.exists():
        sys.exit(f"✗ 找不到 {ENV_FILE}\n  照 b-app/.env.local.example 复制一份再填值。")
    out: dict[str, str] = {}
    for line in ENV_FILE.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        name, _, value = line.partition("=")
        out[name.strip() + "="] = value.strip().strip('"').strip("'")
    return out


def enabled_subspecs(project: Path) -> list[str]:
    """从离线工程的 Podfile 里读**当前真正启用**的模块。

    不写死一份清单：Podfile 是那边的真源，两处各记一份迟早分岔，
    而分岔的表现是「配置生成器要的值和工程实际装的库对不上」——
    那种错会指向完全无关的地方（比如「高德 key 填了但地图还是白的」）。
    """
    podfile = project / "Podfile"
    if not podfile.exists():
        sys.exit(f"✗ {podfile} 不在 —— 这不是 iOS 离线打包工程")
    block = re.search(r"uniapp_subspecs\s*=\s*\[(.*?)^\]", podfile.read_text(encoding="utf-8"),
                      re.S | re.M)
    if not block:
        sys.exit("✗ Podfile 里找不到 uniapp_subspecs 列表 —— SDK 结构变了，这个脚本要跟着改")
    names = []
    for line in block.group(1).splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            continue
        m = re.match(r"'([A-Za-z0-9-]+)'", stripped)
        if m:
            names.append(m.group(1))
    if not names:
        sys.exit("✗ 一个启用的 subspec 都没解析到 —— 判据失效了，不是真的没启用")
    return names


def rb_str(value: str) -> str:
    """Ruby 单引号字符串。key 里只会有字母数字，但转义一下不吃亏。"""
    return "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'"


def main() -> None:
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    project = Path(sys.argv[1]).expanduser().resolve()
    check_only = "--check" in sys.argv

    env = read_env()
    subspecs = enabled_subspecs(project)

    required: list[str] = list(ALWAYS_NEEDED)
    for spec in subspecs:
        required += NEEDED_BY.get(spec, [])
    required = sorted(set(required))

    missing = [p.rstrip("=") for p in required if not env.get(p)]
    if missing:
        sys.exit(
            "✗ b-app/.env.local 里这几个还是空的，而工程启用了要它们的模块：\n"
            + "".join(f"    {name}\n" for name in missing)
            + f"  当前启用的模块：{' '.join(subspecs)}\n"
            "  填了才能打包 —— 少一个的表现不是报错，是真机上那一项静默不工作\n"
            "  （高德缺 key = 定位错误码 7、地图白屏；个推缺 = 拿不到 cid，推送永远收不到）。"
        )

    amap = env.get(AMAP_PREFIX, "")
    getui_id = env.get(GETUI_APPID_PREFIX, "")
    getui_key = env.get(GETUI_APPKEY_PREFIX, "")
    getui_secret = env.get(GETUI_APPSECRET_PREFIX, "")

    content = f"""# 本文件由 b-app/offline/ios/gen-uniapp-config.py 生成，**不要手工改**。
# 值的真源是 b-app/.env.local（gitignore 挡着，不进仓库）。
# 手工改的后果：下次打包被整份覆盖，而你以为改生效了。

UNIAPP_PLIST_VALUES = {{
  map_gaode: {{
    appkey: {rb_str(amap)}
  }},
  push_getui: {{
    appid: {rb_str(getui_id)},
    appkey: {rb_str(getui_key)},
    appsecret: {rb_str(getui_secret)}
  }},

  capabilities: {{
    # 首版不接微信，Universal Links 还没挂，留空
    associated_domains: [],
    # 首版不接 Apple 登录：后端 GRANT_APPLE 还没校验 identityToken
    sign_in_with_apple: false,
    push_notifications: true
  }}
}}.freeze

UNIAPP_UTS_PLUGIN_VALUES = {{}}.freeze
"""

    target = project / "uniapp_config.rb"
    if check_only:
        current = target.read_text(encoding="utf-8") if target.exists() else ""
        if current != content:
            sys.exit(f"✗ {target} 与 .env.local 对不上了，重跑本脚本（不带 --check）")
        print(f"✓ {target.name} 与 .env.local 一致")
        return

    target.write_text(content, encoding="utf-8")

    # 回读：**写完要验**。这个仓库反复栽在「只写不读」上 ——
    # 写进去了不等于值是对的（比如把空串写成了 ''）。
    back = target.read_text(encoding="utf-8")
    for label, value in [("高德", amap), ("个推 appid", getui_id),
                         ("个推 appkey", getui_key), ("个推 appsecret", getui_secret)]:
        if value and rb_str(value) not in back:
            sys.exit(f"✗ 回读失败：{label} 的值没进 {target.name}")
    print(f"✓ 已生成 {target}")
    print(f"  启用模块：{' '.join(subspecs)}")
    print(f"  写入的值：高德 {len(amap)} 位 · 个推 appid {len(getui_id)} 位 "
          f"· appkey {len(getui_key)} 位 · appsecret {len(getui_secret)} 位")
    print("  （只打长度不打值 —— key 不该出现在构建日志和工单里）")


if __name__ == "__main__":
    main()
