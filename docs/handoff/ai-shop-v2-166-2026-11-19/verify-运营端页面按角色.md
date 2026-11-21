# v2 运营端页面按角色验证 2026-11-20 00:27:06
入口 https://tw.neargo.ai/ops-shop-v2；超管可见 156 个页面

## 客服（ST-V2UI-CS）：持有 17 个权限码，菜单 24 个页面
- 逐页打开：24 页，加载出错的 0 页
- 菜单：后端给 24 个页面；侧栏里出现的页面入口 11 个，没有露出他不该看到的页面
- 越权：直接打开 10 个不在他菜单里的页面，页面层面拦住（跳走或提示无权限）3 个；接口由网关逐个判，见下
- 网关判权：漏判 0 处，误拒 0 处
- 页面依赖了他没有的权限（网关拒得对，页面会缺一块）20 处：
  - 越权页 平台设置 / 地区配置 调 /ops/appearance 要 system:theme:read
  - 越权页 平台设置 / 地区配置 调 /ops/rule-texts 要 system:theme:read
  - 越权页 平台设置 / 外观文案 调 /ops/appearance 要 system:theme:read
  - 越权页 平台设置 / 外观文案 调 /ops/rule-texts 要 system:theme:read
  - 越权页 平台设置 / 系统参数 调 /ops/appearance 要 system:theme:read
  - 越权页 平台设置 / 系统参数 调 /ops/rule-texts 要 system:theme:read
  - 越权页 平台设置 / 经营授权 调 /ops/appearance 要 system:theme:read
  - 越权页 平台设置 / 经营授权 调 /ops/rule-texts 要 system:theme:read
  - 越权页 平台设置 / 范围开关 调 /ops/appearance 要 system:theme:read
  - 越权页 平台设置 / 范围开关 调 /ops/rule-texts 要 system:theme:read
  - 越权页 平台设置 / 菜单配置 调 /ops/perm/roles 要 iam:role:read
  - 越权页 平台设置 / 菜单配置 调 /ops/staffs 要 iam:staff:read
  - 越权页 平台设置 / 行业准入 调 /ops/appearance 要 system:theme:read
  - 越权页 平台设置 / 行业准入 调 /ops/rule-texts 要 system:theme:read
  - 越权页 平台设置 / 角色权限 调 /ops/perm/roles 要 iam:role:read

## 财务（ST-V2UI-FINANCE）：持有 20 个权限码，菜单 30 个页面
- 逐页打开：30 页，加载出错的 1 页
- 菜单：后端给 30 个页面；侧栏里出现的页面入口 4 个，没有露出他不该看到的页面
- 越权：直接打开 10 个不在他菜单里的页面，页面层面拦住（跳走或提示无权限）3 个；接口由网关逐个判，见下
- 网关判权：漏判 0 处，误拒 0 处
- 页面依赖了他没有的权限（网关拒得对，页面会缺一块）21 处：
  - 经营财务 / 渠道费率 调 /ops/markets 要 system:param:read
  - 越权页 平台设置 / 地区配置 调 /ops/appearance 要 system:theme:read
  - 越权页 平台设置 / 地区配置 调 /ops/rule-texts 要 system:theme:read
  - 越权页 平台设置 / 外观文案 调 /ops/appearance 要 system:theme:read
  - 越权页 平台设置 / 外观文案 调 /ops/rule-texts 要 system:theme:read
  - 越权页 平台设置 / 系统参数 调 /ops/appearance 要 system:theme:read
  - 越权页 平台设置 / 系统参数 调 /ops/rule-texts 要 system:theme:read
  - 越权页 平台设置 / 经营授权 调 /ops/appearance 要 system:theme:read
  - 越权页 平台设置 / 经营授权 调 /ops/rule-texts 要 system:theme:read
  - 越权页 平台设置 / 范围开关 调 /ops/appearance 要 system:theme:read
  - 越权页 平台设置 / 范围开关 调 /ops/rule-texts 要 system:theme:read
  - 越权页 平台设置 / 菜单配置 调 /ops/perm/roles 要 iam:role:read
  - 越权页 平台设置 / 菜单配置 调 /ops/staffs 要 iam:staff:read
  - 越权页 平台设置 / 行业准入 调 /ops/appearance 要 system:theme:read
  - 越权页 平台设置 / 行业准入 调 /ops/rule-texts 要 system:theme:read

## 商品运营（ST-V2UI-GOODS）：持有 27 个权限码，菜单 39 个页面
- 逐页打开：39 页，加载出错的 1 页
- 菜单：后端给 39 个页面；侧栏里出现的页面入口 6 个，没有露出他不该看到的页面
- 越权：直接打开 10 个不在他菜单里的页面，页面层面拦住（跳走或提示无权限）3 个；接口由网关逐个判，见下
- 网关判权：漏判 0 处，误拒 0 处
- 页面依赖了他没有的权限（网关拒得对，页面会缺一块）7 处：
  - 商品资料 / 商品管理 调 /ops/merchants 要 merchant:merchant:read
  - 越权页 平台设置 / 菜单配置 调 /ops/perm/roles 要 iam:role:read
  - 越权页 平台设置 / 菜单配置 调 /ops/staffs 要 iam:staff:read
  - 越权页 平台设置 / 角色权限 调 /ops/perm/roles 要 iam:role:read
  - 越权页 平台设置 / 角色权限 调 /ops/staffs 要 iam:staff:read
  - 越权页 平台设置 / 运营账号 调 /ops/perm/roles 要 iam:role:read
  - 越权页 平台设置 / 运营账号 调 /ops/staffs 要 iam:staff:read

## 结论：2 处要看
- 财务：经营财务 / 退款分账（/finance?tab=refund-back）→ code=10500 /ops/refund-split-backs；加载失败；系统开小差
- 商品运营：进销存 / 库存对差（/inventory?tab=recon）→ 404 /ops/jobs/inv-recon/logs
