# TDD-元器件 · 独立成项目 ai-key

> 2026-09-30 · 状态：**方案 · 待确认**（§五 四个决定点等拍板后再动手）
> 档位：2（拆仓库 · 新账号体系 · 跨仓依赖 · 不可逆决策）
> 需求（用户原话归纳）：
> ① 元器件搬到 `~/work/ai/ai-key`，作为独立项目；② 基础能力可以依赖 ai-shop；
> ③ 小程序一个账号同时支持买家（C）与供应商（B），**账号与 ai-shop 的分开**；④ 将来用独立的小程序 appid；
> ⑤ 运营端暂时仍接在 ai-shop 的运营端里。

---

## 一、现状：元器件与 ai-shop 缠在哪里

| 面 | 现在怎么缠着 | 拆开后 |
|---|---|---|
| 代码位置 | `backend/elec/{elec-api,elec-core,elec-svc}` · `elec-app/` · 文档、原型、生成器都在 ai-shop 仓库 | 搬进 ai-key（`elec-api` 除外，见下） |
| 后端基础库 | 父 POM `shop-parent`；`shop-base`（统一响应、限流、异常）、`shop-base-auth`（令牌过滤器基类、登录态）、`shop-store-mybatis`、`svc-client`（服务间调用） | **继续依赖**（需求 ②），以本机 `~/.m2` 里的构件引用 |
| 错误码 | 14 个 `9xxxx` 写在 ai-shop 的 `ErrorCode` 枚举里，`BizException` 只认这个枚举；文案在 ai-shop 的文案文件 | ai-key **自带**错误码、异常与文案 —— 它没法往 ai-shop 的枚举里加码 |
| 账号 | 买家与供应商都是 **ai-shop 的 C 端账号**：登录走 `/mp/user/*`，令牌 `ctk_` 由主系统认（`/internal/elec/session`），`elc_rfq.buyer_ref` / `elc_supplier_member.account_ref` 存的是 ai-shop 的 `usr_no` | **自己的账号体系**（需求 ③），见 §三 |
| 通知 | 站内信进 ai-shop 的收件箱；订阅消息由主系统按 `usr_no` 找 openid、用虹选的 access_token 发 | 站内信不再借（账号分开后那个收件箱看不见）；订阅消息按 (appid, openid) 发，过渡期借 ai-shop 的通道 |
| 前端 | `elec-app` 依赖 `@ai-shop/ui`（组件）与 `packages/shared`（类型、请求客户端、登录/持久化/订阅授权封装） | 组件库继续依赖（需求 ②）；元器件自己的类型与登录封装搬进 ai-key |
| 小程序 | 测试期并进虹选（`c-app/src/pkg-elec` 软链到 `elec-app/src`，`scripts/with-elec.mjs`） | 过渡期照旧并进虹选；有了独立 appid 后停止并包（需求 ④） |
| 运营端 | ops-web 的「元器件」菜单与四个子页，V370/V371 的菜单与岗位授权，运营令牌由主系统认 | **全部留在 ai-shop**（需求 ⑤） |
| 部署 | `deploy-backend.sh` 的 elec-svc 分支、`ai-shop-elec.service`、nginx `location ^~ /elec/`、库 `ai_shop_elec` | 部署脚本搬进 ai-key；服务名、库名、nginx 路由**不改** |
| 闸门 | ai-shop 的 pre-push 里跑元器件测试、ER 生成器、`elc_*` 枚举对账、`Elec*` 枚举登记 | ai-shop 去掉这些；ai-key 建自己的 pre-push |

**生产数据现状**：`ai_shop_elec` 里 1 个供应商、0 张询价、0 行库存；V1、V2 已执行（冻结），V3（库存上传二期）未执行。

---

## 二、拆完之后的样子

```
~/work/ai/ai-key/                      独立 git 仓库
  backend/
    pom.xml                            ai-key 自己的父 POM（继承 shop-parent 拿版本管理，或自管 —— 见决定点 D3）
    key-core/                          ← elec-core（实体、Mapper、服务、控制器、迁移 db/elec/V*）
    key-svc/                           ← elec-svc（启动类、安全链、账号、主系统客户端、企业微信）
  app/                                 ← elec-app（uni-app 小程序）
  docs/  prototypes/  scripts/  deploy/
  CLAUDE.md   .githooks/pre-push

~/work/ai/ai-shop/                     保留
  backend/elec/elec-api/               **留在 ai-shop**：它是 ai-shop 对外提供的内部接口契约，ai-key 依赖它
  backend/shop-app/.../InternalElecEndpoint.java   只剩：认运营令牌、借短信通道、借订阅消息通道、借 code2Session
  ops-web/app/elec/ …                  运营端四页照旧
  V370 / V371                          菜单与岗位授权照旧
```

**依赖方向只有一个**：ai-key → ai-shop（编译期依赖基础库构件；运行期调 ai-shop 的内部接口）。ai-shop 不引用 ai-key 的任何代码。

---

## 三、账号：与 ai-shop 分开，一个账号两个面

### 3.1 模型

在 `ai_shop_elec` 里新建（V4 起，V1–V3 不动）：

| 表 | 做什么 |
|---|---|
| `elc_account` | 元器件账号：`account_no`（业务键）、`phone`（**主键语义**：一个手机号一个账号）、昵称、状态 |
| `elc_account_identity` | 登录身份：`(appid, openid)` → `account_no`，另存 `unionid`。**按 appid 分行** —— 过渡期是虹选的 appid，将来是独立 appid，两行挂在同一个账号下 |
| `elc_session` | 令牌（前缀 `ktk_`）。复用 ai-shop `shop-base-auth` 的 `TokenStore` 抽象，落在元器件自己的库里 |
| `elc_otp` 或内存 | 验证码的「存与校验」在 ai-key；「发短信」借 ai-shop（短信签名与通道是 ai-shop 的资质） |

买家面与供应商面**仍是同一个账号**（需求 ③，与现在的双角色规则一致）：`/elec/c/**` 是买家，`/elec/b/**` 看 `elc_supplier_member` 判是不是供应商。现有的七条双角色规则（[接口总览与双角色](../../../ai-key/docs/technical/TDD-元器件-接口总览与双角色.md) §三）不变，只是「账号」从 ai-shop 的 `usr_no` 换成 `account_no`。

### 3.2 登录

| 接口（ai-key 新增） | 做什么 |
|---|---|
| `POST /elec/auth/otp` | 发验证码（ai-key 生成与存，调 ai-shop 内部接口发短信） |
| `POST /elec/auth/login` | 手机号 + 验证码登录；带 `wxCode` 时顺便把 (appid, openid) 挂到账号上 |
| `POST /elec/auth/wx` | 微信静默登录：code → (appid, openid)；挂过就直接登录，没挂过回「要绑手机号」 |
| `POST /elec/auth/phone/wx` | 微信一键取号（要小程序已认证） |
| `POST /elec/auth/logout` | 退出 |

**code → openid 这一步谁来做**：要用 appid 的 secret。
- 过渡期（仍在虹选里）：**借 ai-shop 的内部接口**做 code2Session —— 虹选的 appsecret 不复制到第二个地方
- 独立 appid 之后：ai-key 在 `elec.env` 里配自己的 appid/secret，自己调微信

### 3.3 令牌怎么认

`ElecTokenFilter` 按前缀分流：

| 前缀 | 谁发的 | 怎么认 |
|---|---|---|
| `ktk_` | ai-key | 自己的 `elc_session` |
| `otk_` | ai-shop 运营端 | 照旧调 `/internal/elec/session`（只认 OPERATOR） |
| `ctk_` | ai-shop 的 C 端 | **切换后一律 401**（账号已分开）；切换当天端上清令牌重新登录 |

### 3.4 存量数据

`elc_supplier_member.account_ref` 与 `elc_rfq.buyer_ref` 现在存的是 ai-shop 的 `usr_no`。迁移（V5，一次性）：按手机号建 `elc_account`（手机号由切换脚本经 ai-shop 内部接口取一次），把这两列改写成新的 `account_no`。**生产只有 1 个供应商、0 张询价**，量可以忽略，但仍走迁移，不手改库。

---

## 四、通知改道

| 通道 | 现在 | 拆开后 |
|---|---|---|
| 订阅消息 | 主系统按 `usr_no` 找 openid 发 | ai-key 自己知道 (appid, openid)。过渡期调 ai-shop 内部接口「给虹选下的这个 openid 发这条」（access_token 在 ai-shop）；独立 appid 后 ai-key 自己发、在新 appid 上重选模板 |
| 站内信 | 进 ai-shop 的收件箱 | **不再借**：元器件账号在那个收件箱里不存在。端上靠 `/elec/me` 的角标 + 订阅消息；真需要消息中心再在 ai-key 建 |
| 企业微信 | elec-svc 自己发 | 不变 |

ai-shop 的 `InternalElecEndpoint` 相应改为：认运营令牌 · 发短信 · 按 openid 发订阅消息 · code2Session。**不再按 `usr_no` 做任何事。**

---

## 五、决定点（要你定）

| # | 问题 | 推荐 | 另一选项 |
|---|---|---|---|
| D1 | 搬家要不要保留提交历史 | **不保留**：ai-key 首个提交注明来源（ai-shop 的某个 SHA），历史留在 ai-shop 里查 | 保留：要先装 `git filter-repo`（本机没有，要下载），把 5 个目录的历史抽出来 |
| D2 | Java 包名 `ai.neargo.shop.elec` 要不要改成 `ai.neargo.key` | **搬完、测试全绿之后单独一步改**，搬家那一步不改名（出错时好定位） | 搬家时一起改；或不改 |
| D3 | 过渡期登录方式 | **手机号 + 验证码为主、微信静默登录为辅**（借 ai-shop 做虹选的 code2Session）—— 任何 appid 下都能用 | 只做手机号验证码；或等独立 appid 再做微信登录 |
| D4 | 有独立 appid 之前，要不要继续并进虹选测试 | **继续并进**（并包脚本搬到 ai-key，软链指向 ai-key）| 不并进，只用独立构建在开发者工具里测 |

另有两件**不改**，先写明：生产的服务名 `ai-shop-elec`、库名 `ai_shop_elec`、nginx 路由 `/elec/` 都保持原样 —— 改名要动生产、没有收益。

---

## 六、步骤（每步可单独验证、可停）

| 步 | 做什么 | 验证 | 动不动生产 |
|---|---|---|---|
| 0 | **冻结**：通知前端会话与库存上传会话，搬家期间 ai-shop 里的元器件代码不再改；记下来源 SHA | 各会话确认 | 否 |
| 1 | **建仓搬家，不改行为**：建 `~/work/ai/ai-key`，搬入 `elec-core`、`elec-svc`、`elec-app`、元器件文档/原型/生成器/部署脚本；ai-key 的 POM 依赖 ai-shop 的基础库构件（先在 ai-shop 执行 `mvn install` 装到本机 `~/.m2`）；前端以 `file:` 引用 ai-shop 的组件库 | ai-key 里后端全部测试绿（现 88+ 条）、小程序 H5 与微信产物都能构建；**迁移文件逐字节不变**（V1/V2 已在生产执行） | 否 |
| 2 | **ai-shop 瘦身**：删掉已搬走的目录；留 `elec-api`、`InternalElecEndpoint`、运营端、V370/V371；去掉 pre-push 里元器件相关的闸门与登记 | ai-shop 整套 pre-push 绿 | 否 |
| 3 | **错误码独立**：ai-key 自带错误码、异常、全局异常处理与三语文案；码值保持 `9xxxx` 不变（端上按码分流）；之后 ai-shop 删掉那 14 个码 | 两边测试绿；端上错误码对照不变 | 否 |
| 4 | **(可选) 改包名**（D2） | 测试绿 | 否 |
| 5 | **独立账号**：V4 建账号三张表；登录接口；令牌前缀分流；小程序登录改走 `/elec/auth/*`；ai-shop 内部接口加「发短信 / code2Session / 按 openid 发订阅」三个 | 端到端测试：同一账号两个面、`ctk_` 被拒、运营令牌照常 | 否（代码就绪） |
| 6 | **切换上线**（要你点头）：先在生产库副本上跑 V4/V5；发 ai-shop（新内部接口）→ 发 ai-key（跑 V4/V5、改写两列）→ 发小程序新版本 | 生产回读：供应商那 1 条已挂到新账号；旧令牌 401、新登录可用 | **是** |
| 7 | **将来 · 独立 appid**（需求 ④）：你注册新小程序 → `elec.env` 配 appid/secret → 新 appid 上选订阅模板 → 小程序改 appid 上传 → 停止并进虹选；已有账号在新 appid 下首次登录按手机号认回 | 真机登录与订阅消息 | 是 |

第 3 步之前两边都能各自独立发版；**第 6 步是唯一一次必须两个仓库按顺序一起发的**。

**库存上传二期（`dd2b61fdb` 起，V3 未上线）**跟着第 1 步一起搬进 ai-key，它的上线仍由那个会话验证完、你来定。

---

## 七、风险

| 风险 | 影响 | 缓解 |
|---|---|---|
| 搬家时迁移文件被改动（换行、编码、路径） | 生产起不来（V1/V2 已冻结） | 第 1 步逐字节比对；`ElecAppliedMigrationsFrozenTest` 一起搬过去；迁移仍放 `classpath:db/elec` |
| ai-key 依赖 ai-shop 的 SNAPSHOT 构件，ai-shop 一改基础库 ai-key 就变 | 编译或行为漂移 | 构件依赖写死版本；ai-shop 基础库有破坏性改动时，在 ai-key 里跑一遍全量测试再升 |
| 切换当天所有元器件用户要重新登录 | 体验中断（现在用户极少） | 选在无单时段；端上遇到 401 自动清令牌跳登录（已有逻辑） |
| 过渡期仍借虹选的 appid | 买家在微信里看到的是「虹选」 | 这是 D4 的已知代价，独立 appid 后解除 |
| 两个仓库各自的 pre-push 看不到对方 | 契约（`elec-api`、内部接口）改了一边没改另一边 | 契约只在 ai-shop 一处定义；ai-key 的 pre-push 编译时拉最新的 `elec-api` 构件 |
