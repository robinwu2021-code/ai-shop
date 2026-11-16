# TDD · 运营端 · 品牌名称配置与登录页整洁化

> 状态：**已实现并发布 UME166**（2026-11-16，发布 `brand-login-20261116-b88ea3a41`，[发布记录](../../../handoff/ops-list-drawer-2026-11-16/release-166-brand-login.json)）· 档位 1：新增配置文件 `brand.json`、增删 i18n 词条；不改接口、库表、权限码。
> 原型：[ops-login](../../../../prototypes/ops-login.html)（[发布版](https://claude.ai/artifact/Pyz6pDYjBksEhyJwr5XqJ9)，v3）

## §0 范围与结论

运营端名称改为「NearGo平台运营」，且名称可配置。登录页保持线上「浅底 + 居中卡片」布局，只做整洁化：标题、标识、副标题、语言切换位置、显示密码、6 格验证码、忘记密码原地切换、非生产环境标签。

## §1 配置

| 项 | 说明 |
|---|---|
| 文件 | `ops-web/public/brand.json`，构建后在 `/ops-shop/brand.json`；公开、免登录（登录页在登录前就要用） |
| 字段 | `name`（名称）· `mark`（方形标识里的字）· `logoUrl`（可选，图片标识）· `env`（`""` 生产 / `test` / `staging`）· `locales`（按语言覆盖，如 `{"en": {"name": "NearGo Ops"}}`） |
| 取值顺序 | `brand.json` → 构建时 `NEXT_PUBLIC_BRAND_NAME` / `NEXT_PUBLIC_BRAND_MARK` / `NEXT_PUBLIC_BRAND_ENV` → 代码默认值 |
| 失败 | 文件缺失、不是 JSON、字段类型不对：逐项回落到下一层，不报错、不阻塞登录 |
| 出现位置 | 浏览器标签标题、登录卡片、侧栏顶部标识与名称、桌面通知标题 |
| 服务器上改名 | 改发布目录里的 `ops-shop/brand.json`；下次发布会被仓库里的版本覆盖，长期改名要同时改仓库文件 |

不放进后台「平台设置」：登录前读不到要登录的接口。以后要在界面里改名，需新增免登录接口，另走 TDD。

## §2 实施

1. `lib/brand.ts`：类型、默认值、合并规则（纯函数，可单测）；`lib/stores/brand.ts`：加载一次 `brand.json` 的 store 与 `useBrand()`（按当前语言合并）。
2. `Providers` 启动时加载并同步 `document.title`；`app/layout.tsx` 静态标题改默认名称。
3. 侧栏 `rail.tsx`、桌面通知 `notify-bell.tsx` 改用 `useBrand()`。
4. 登录页按原型 v3 重写；忘记密码由抽屉改为卡片内原地切换。
5. i18n：删除 `common.appName`、`common.appTitle`、`bell.desktopTitle`、`login.subtitle`（名称不再走词典）；新增 `login.hint`、`login.showPassword`、`login.hidePassword`、`login.envTest`、`login.envStaging`、`login.backToLogin`、`login.forgotEmail`。中文、繁体、英文三份同步。

## §5 验证

- `lib/brand.test.ts`：默认值、`brand.json` 覆盖、按语言覆盖、非法值回落。
- 运营端 tsc、eslint、vitest；本地 mock 下登录页四种状态（默认、验证码、出错 + 测试环境、忘记密码）与窄屏的浏览器点验。
