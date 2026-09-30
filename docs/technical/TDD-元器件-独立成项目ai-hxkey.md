# TDD-元器件 · 独立成项目 ai-hxkey

> 2026-09-30 · 状态：**第 1–6 步完成，已上线**（2026-09-30 14:50，elec-svc = ai-hxkey `bdafe1a`，小程序体验版 0.1.74）；第 7 步（独立 appid）待将来（§五 按推荐拍板；进度与偏差见 §六½）
> 档位：2（拆仓库 · 新账号体系 · 跨仓依赖 · 不可逆决策）
> 需求（用户原话归纳）：
> ① 元器件搬到 `~/work/ai/ai-hxkey`，作为独立项目；② 基础能力可以依赖 ai-shop；
> ③ 小程序一个账号同时支持买家（C）与供应商（B），**账号与 ai-shop 的分开**；④ 将来用独立的小程序 appid；
> ⑤ 运营端暂时仍接在 ai-shop 的运营端里。

---

## 一、现状：元器件与 ai-shop 缠在哪里

| 面 | 现在怎么缠着 | 拆开后 |
|---|---|---|
| 代码位置 | `backend/elec/{elec-api,elec-core,elec-svc}` · `elec-app/` · 文档、原型、生成器都在 ai-shop 仓库 | 搬进 ai-hxkey（`elec-api` 除外，见下） |
| 后端基础库 | 父 POM `shop-parent`；`shop-base`（统一响应、限流、异常）、`shop-base-auth`（令牌过滤器基类、登录态）、`shop-store-mybatis`、`svc-client`（服务间调用） | **继续依赖**（需求 ②），以本机 `~/.m2` 里的构件引用 |
| 错误码 | 14 个 `9xxxx` 写在 ai-shop 的 `ErrorCode` 枚举里，`BizException` 只认这个枚举；文案在 ai-shop 的文案文件 | ai-hxkey **自带**错误码、异常与文案 —— 它没法往 ai-shop 的枚举里加码 |
| 账号 | 买家与供应商都是 **ai-shop 的 C 端账号**：登录走 `/mp/user/*`，令牌 `ctk_` 由主系统认（`/internal/elec/session`），`elc_rfq.buyer_ref` / `elc_supplier_member.account_ref` 存的是 ai-shop 的 `usr_no` | **自己的账号体系**（需求 ③），见 §三 |
| 通知 | 站内信进 ai-shop 的收件箱；订阅消息由主系统按 `usr_no` 找 openid、用虹选的 access_token 发 | 站内信不再借（账号分开后那个收件箱看不见）；订阅消息按 (appid, openid) 发，过渡期借 ai-shop 的通道 |
| 前端 | `elec-app` 依赖 `@ai-shop/ui`（组件）与 `packages/shared`（类型、请求客户端、登录/持久化/订阅授权封装） | 组件库继续依赖（需求 ②）；元器件自己的类型与登录封装搬进 ai-hxkey |
| 小程序 | 测试期并进虹选（`c-app/src/pkg-elec` 软链到 `elec-app/src`，`scripts/with-elec.mjs`） | 过渡期照旧并进虹选；有了独立 appid 后停止并包（需求 ④） |
| 运营端 | ops-web 的「元器件」菜单与四个子页，V370/V371 的菜单与岗位授权，运营令牌由主系统认 | **全部留在 ai-shop**（需求 ⑤） |
| 部署 | `deploy-backend.sh` 的 elec-svc 分支、`ai-shop-elec.service`、nginx `location ^~ /elec/`、库 `ai_shop_elec` | 部署脚本搬进 ai-hxkey；服务名、库名、nginx 路由**不改** |
| 闸门 | ai-shop 的 pre-push 里跑元器件测试、ER 生成器、`elc_*` 枚举对账、`Elec*` 枚举登记 | ai-shop 去掉这些；ai-hxkey 建自己的 pre-push |

**生产数据现状**：`ai_shop_elec` 里 1 个供应商、0 张询价、0 行库存；V1、V2 已执行（冻结），V3（库存上传二期）未执行。

---

## 二、拆完之后的样子

```
~/work/ai/ai-hxkey/                      独立 git 仓库
  backend/
    pom.xml                            ai-hxkey 自己的父 POM（继承 shop-parent 拿版本管理，或自管 —— 见决定点 D3）
    key-core/                          ← elec-core（实体、Mapper、服务、控制器、迁移 db/elec/V*）
    key-svc/                           ← elec-svc（启动类、安全链、账号、主系统客户端、企业微信）
  app/                                 ← elec-app（uni-app 小程序）
  docs/  prototypes/  scripts/  deploy/
  CLAUDE.md   .githooks/pre-push

~/work/ai/ai-shop/                     保留
  backend/elec/elec-api/               **留在 ai-shop**：它是 ai-shop 对外提供的内部接口契约，ai-hxkey 依赖它
  backend/shop-app/.../InternalElecEndpoint.java   只剩：认运营令牌、借短信通道、借订阅消息通道、借 code2Session
  ops-web/app/elec/ …                  运营端四页照旧
  V370 / V371                          菜单与岗位授权照旧
```

**依赖方向只有一个**：ai-hxkey → ai-shop（编译期依赖基础库构件；运行期调 ai-shop 的内部接口）。ai-shop 不引用 ai-hxkey 的任何代码。

---

## 三、账号：与 ai-shop 分开，一个账号两个面

### 3.1 模型

在 `ai_shop_elec` 里新建（V4 起，V1–V3 不动）：

| 表 | 做什么 |
|---|---|
| `elc_account` | 元器件账号：`account_no`（业务键）、`phone`（**主键语义**：一个手机号一个账号）、昵称、状态 |
| `elc_account_identity` | 登录身份：`(appid, openid)` → `account_no`，另存 `unionid`。**按 appid 分行** —— 过渡期是虹选的 appid，将来是独立 appid，两行挂在同一个账号下 |
| `elc_session` | 令牌（前缀 `ktk_`）。复用 ai-shop `shop-base-auth` 的 `TokenStore` 抽象，落在元器件自己的库里 |
| `elc_otp` 或内存 | 验证码的「存与校验」在 ai-hxkey；「发短信」借 ai-shop（短信签名与通道是 ai-shop 的资质） |

买家面与供应商面**仍是同一个账号**（需求 ③，与现在的双角色规则一致）：`/elec/c/**` 是买家，`/elec/b/**` 看 `elc_supplier_member` 判是不是供应商。现有的七条双角色规则（ai-hxkey 的 `docs/technical/TDD-元器件-接口总览与双角色.md` §三）不变，只是「账号」从 ai-shop 的 `usr_no` 换成 `account_no`。

### 3.2 登录

| 接口（ai-hxkey 新增） | 做什么 |
|---|---|
| `POST /elec/auth/otp` | 发验证码（ai-hxkey 生成与存，调 ai-shop 内部接口发短信） |
| `POST /elec/auth/login` | 手机号 + 验证码登录；带 `wxCode` 时顺便把 (appid, openid) 挂到账号上 |
| `POST /elec/auth/wx` | 微信静默登录：code → (appid, openid)；挂过就直接登录，没挂过回「要绑手机号」 |
| `POST /elec/auth/phone/wx` | 微信一键取号（要小程序已认证） |
| `POST /elec/auth/logout` | 退出 |

**code → openid 这一步谁来做**：要用 appid 的 secret。
- 过渡期（仍在虹选里）：**借 ai-shop 的内部接口**做 code2Session —— 虹选的 appsecret 不复制到第二个地方
- 独立 appid 之后：ai-hxkey 在 `elec.env` 里配自己的 appid/secret，自己调微信

### 3.3 令牌怎么认

`ElecTokenFilter` 按前缀分流：

| 前缀 | 谁发的 | 怎么认 |
|---|---|---|
| `ktk_` | ai-hxkey | 自己的 `elc_session` |
| `otk_` | ai-shop 运营端 | 照旧调 `/internal/elec/session`（只认 OPERATOR） |
| `ctk_` | ai-shop 的 C 端 | **切换后一律 401**（账号已分开）；切换当天端上清令牌重新登录 |

### 3.4 存量数据

`elc_supplier_member.account_ref` 与 `elc_rfq.buyer_ref` 现在存的是 ai-shop 的 `usr_no`。迁移（V5，一次性）：按手机号建 `elc_account`（手机号由切换脚本经 ai-shop 内部接口取一次），把这两列改写成新的 `account_no`。**生产只有 1 个供应商、0 张询价**，量可以忽略，但仍走迁移，不手改库。

---

## 四、通知改道

| 通道 | 现在 | 拆开后 |
|---|---|---|
| 订阅消息 | 主系统按 `usr_no` 找 openid 发 | ai-hxkey 自己知道 (appid, openid)。过渡期调 ai-shop 内部接口「给虹选下的这个 openid 发这条」（access_token 在 ai-shop）；独立 appid 后 ai-hxkey 自己发、在新 appid 上重选模板 |
| 站内信 | 进 ai-shop 的收件箱 | **不再借**：元器件账号在那个收件箱里不存在。端上靠 `/elec/me` 的角标 + 订阅消息；真需要消息中心再在 ai-hxkey 建 |
| 企业微信 | elec-svc 自己发 | 不变 |

ai-shop 的 `InternalElecEndpoint` 相应改为：认运营令牌 · 发短信 · 按 openid 发订阅消息 · code2Session。**不再按 `usr_no` 做任何事。**

---

## 五、决定点（要你定）

| # | 问题 | 推荐 | 另一选项 |
|---|---|---|---|
| D1 | 搬家要不要保留提交历史 | **不保留**：ai-hxkey 首个提交注明来源（ai-shop 的某个 SHA），历史留在 ai-shop 里查 | 保留：要先装 `git filter-repo`（本机没有，要下载），把 5 个目录的历史抽出来 |
| D2 | Java 包名 `ai.neargo.shop.elec` 要不要改成 `ai.neargo.key` | **搬完、测试全绿之后单独一步改**，搬家那一步不改名（出错时好定位） | 搬家时一起改；或不改 |
| D3 | 过渡期登录方式 | **手机号 + 验证码为主、微信静默登录为辅**（借 ai-shop 做虹选的 code2Session）—— 任何 appid 下都能用 | 只做手机号验证码；或等独立 appid 再做微信登录 |
| D4 | 有独立 appid 之前，要不要继续并进虹选测试 | **继续并进**（并包脚本搬到 ai-hxkey，软链指向 ai-hxkey）| 不并进，只用独立构建在开发者工具里测 |

另有两件**不改**，先写明：生产的服务名 `ai-shop-elec`、库名 `ai_shop_elec`、nginx 路由 `/elec/` 都保持原样 —— 改名要动生产、没有收益。

---

## 六、步骤（每步可单独验证、可停）

| 步 | 做什么 | 验证 | 动不动生产 |
|---|---|---|---|
| 0 | **冻结**：通知前端会话与库存上传会话，搬家期间 ai-shop 里的元器件代码不再改；记下来源 SHA | 各会话确认 | 否 |
| 1 | **建仓搬家，不改行为**：建 `~/work/ai/ai-hxkey`，搬入 `elec-core`、`elec-svc`、`elec-app`、元器件文档/原型/生成器/部署脚本；ai-hxkey 的 POM 依赖 ai-shop 的基础库构件（先在 ai-shop 执行 `mvn install` 装到本机 `~/.m2`）；前端以 `file:` 引用 ai-shop 的组件库 | ai-hxkey 里后端全部测试绿（现 88+ 条）、小程序 H5 与微信产物都能构建；**迁移文件逐字节不变**（V1/V2 已在生产执行） | 否 |
| 2 | **ai-shop 瘦身**：删掉已搬走的目录；留 `elec-api`、`InternalElecEndpoint`、运营端、V370/V371；去掉 pre-push 里元器件相关的闸门与登记 | ai-shop 整套 pre-push 绿 | 否 |
| 3 | **错误码独立**：ai-hxkey 自带错误码、异常、全局异常处理与三语文案；码值保持 `9xxxx` 不变（端上按码分流）；之后 ai-shop 删掉那 14 个码 | 两边测试绿；端上错误码对照不变 | 否 |
| 4 | **(可选) 改包名**（D2） | 测试绿 | 否 |
| 5 | **独立账号**：V4 建账号三张表；登录接口；令牌前缀分流；小程序登录改走 `/elec/auth/*`；ai-shop 内部接口加「发短信 / code2Session / 按 openid 发订阅」三个 | 端到端测试：同一账号两个面、`ctk_` 被拒、运营令牌照常 | 否（代码就绪） |
| 6 | **切换上线**（要你点头）：先在生产库副本上跑 V4/V5；发 ai-shop（新内部接口）→ 发 ai-hxkey（跑 V4/V5、改写两列）→ 发小程序新版本 | 生产回读：供应商那 1 条已挂到新账号；旧令牌 401、新登录可用 | **是** |
| 7 | **将来 · 独立 appid**（需求 ④）：你注册新小程序 → `elec.env` 配 appid/secret → 新 appid 上选订阅模板 → 小程序改 appid 上传 → 停止并进虹选；已有账号在新 appid 下首次登录按手机号认回 | 真机登录与订阅消息 | 是 |

第 3 步之前两边都能各自独立发版；**第 6 步是唯一一次必须两个仓库按顺序一起发的**。

**库存上传二期（`dd2b61fdb` 起，V3 未上线）**跟着第 1 步一起搬进 ai-hxkey，它的上线仍由那个会话验证完、你来定。

## 六½、执行记录（2026-09-30）

**拍板**：用户「按照方案迁移」—— §五 四项全按推荐（不保留历史 · 包名后改 · 手机号验证码为主 + 微信静默 · 继续并进虹选）。

| 步 | 结果 | 证据 |
|---|---|---|
| 1 | ✅ `~/work/ai/ai-hxkey` 建成，来源 `383494c1b` | ai-hxkey 后端全部测试绿；两支迁移逐字节与来源一致；ai-hxkey 自带 pre-push 与 `scripts/check-head.sh` |
| 2 | ✅ ai-shop 瘦身：`4b3520325`（删 220 个文件、改登记与守卫）· `2a20d12fb`（八份生成物）· 本提交（断链） | ai-shop 整套 pre-push |
| 3 | ✅ 错误码独立：ai-hxkey `d281cc8`（`ElecErrorCode` 18 个码、`ElecException`、`ElecExceptionHandler`；58 处抛出点）· ai-shop 本提交（删 18 个码与三语文案，`ErrorCodeUniqueTest` 加「9xxxx 保留」） | ai-hxkey 全量 81 + 93 绿；消融：去掉处理器 → `ElecChooseFlowTest` 三条 90011 变 10500 |
| 4 | ✅ 包名 `ai.neargo.shop.elec` → `ai.neargo.key`：ai-hxkey `b97ac36` | ai-hxkey pre-push 全绿（后端 187 · 端上 43）|
| 5 | ✅ 独立账号：ai-shop `4cef6e62d`（四个内部代办端点）· ai-hxkey `ca7a936`（V5 四张表、`/elec/auth/**`、`ktk_`、订阅额度、存量改写）· `b16fab2`（小程序登录与 `token_elec`）。设计与切换清单在 ai-hxkey 的 `docs/technical/TDD-元器件-独立账号.md` | 本机 MariaDB 临时库冒烟：V1–V5 空库执行、登录全链路（见那篇 §6） |
| 6 | ✅ 已上线：ai-hxkey `bdafe1a`（V3–V5 同时上）；ai-shop 四个内部端点随 `299b06b77` 已在线；小程序体验版 0.1.74（`b6a5d43de`） | 生产库副本彩排 → 备份 → 发布 → 回读，详见 ai-hxkey 那篇 §7 |
| 6½ | ✅ 同日追加（用户定：项目名 hxkey）：仓库 github.com/robinwu2021-code/ai-hxkey（本机 `~/work/ai/ai-hxkey`，旧目录改名 `ai-key.retired-20260930`）；包名 `ai.neargo.hxkey`、坐标 `ai.neargo.hxkey:hxkey-parent`（`890698e`）；生产改名：服务 `hxkey`、目录 `/data/app/hxkey`、库 `hxkey`（账号 `hxkey`）、日志 `/data/log/hxkey`（`971cdbe`；15:04:49 切换，停机约 14 秒，逐表行数一致）；每日备份补上 `hxkey`（`d621b68a6`） | 库副本上以 deploy 身份彩排；回读：连接全在 `hxkey` 库、外部入口 200/10400、老供应商账号在；旧服务 disable、旧库留作回滚 |

**与方案的偏差**（都比方案保守，记下来免得后人按方案去找）：

- **并包脚本没搬**：`c-app/scripts/with-elec.mjs` 留在 ai-shop，只把源路径改指 `../ai-hxkey/app/src` —— 它是虹选 c-app 构建的一部分，搬走了虹选那边反而要跨仓库调脚本。D4 结束（独立 appid）时随并包一起删。
- **岗位授权那篇留在 ai-shop**：`TDD-元器件-运营端岗位授权.md` 讲的是主系统的 V371 与 `Perms`，归 ai-shop；其余元器件文档都已搬走。
- **错误码文案的对账测试暂时跨仓库读**：ai-hxkey 的 `ElecMessagesParityTest` 读 ai-shop 的 `i18n/`（`AI_SHOP_HOME` 或 `~/work/ai/ai-shop`），第 3 步错误码独立后删。
- **第 3 步多踩出一处**：`ElecStockImportServiceImpl` 读表失败的 `catch (BizException)` 要一起换成 `ElecException` —— 不换也能编译，只是失败记录静默不写。
- **第 4 步多踩出一处**：ai-shop 的 `GlobalExceptionHandler` / `ApiResponseWrapper` 限定了 `basePackages = "ai.neargo.shop"`，
  改包后直接引等于不生效（先不修跑了一遍：106 条里 93 条红）。ai-hxkey 用只换注解范围的子类 `KeyWebAdvice`。
- **第 5 步的产品退步，要你定**：库存到期提醒原先只进 ai-shop 的站内信；账号分开后元器件账号不在那个收件箱里，
  这条提醒没有通道了（供应商只剩 `/elec/me` 的角标）。可选：接受；或改走订阅消息（会吃掉「有新求购」的授权额度）。
- **跨仓库的链接一律写成代码文字**：`../../../ai-hxkey/…` 在本机能点，但 pre-push 在 HEAD 干净副本里判，文档规范守卫会把它当断链。本仓库指向元器件文档的地方统一链到 [元器件-已迁到ai-hxkey](./元器件-已迁到ai-hxkey.md)。

---

## 七、风险

| 风险 | 影响 | 缓解 |
|---|---|---|
| 搬家时迁移文件被改动（换行、编码、路径） | 生产起不来（V1/V2 已冻结） | 第 1 步逐字节比对；`ElecAppliedMigrationsFrozenTest` 一起搬过去；迁移仍放 `classpath:db/elec` |
| ai-hxkey 依赖 ai-shop 的 SNAPSHOT 构件，ai-shop 一改基础库 ai-hxkey 就变 | 编译或行为漂移 | 构件依赖写死版本；ai-shop 基础库有破坏性改动时，在 ai-hxkey 里跑一遍全量测试再升 |
| 切换当天所有元器件用户要重新登录 | 体验中断（现在用户极少） | 选在无单时段；端上遇到 401 自动清令牌跳登录（已有逻辑） |
| 过渡期仍借虹选的 appid | 买家在微信里看到的是「虹选」 | 这是 D4 的已知代价，独立 appid 后解除 |
| 两个仓库各自的 pre-push 看不到对方 | 契约（`elec-api`、内部接口）改了一边没改另一边 | 契约只在 ai-shop 一处定义；ai-hxkey 的 pre-push 编译时拉最新的 `elec-api` 构件 |
