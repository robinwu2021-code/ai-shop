# TDD-入驻意向与运营触达

状态：**批 1 已实现（2026-09-29）** · 批 2–4 待开工 · 拍板见 §0.2
关联需求：[多门店与分享激励-需求](../../requirements/多门店与分享激励-需求.md) §3.2.1 ·
[C 端功能清单](../../requirements/C端功能清单.md)
关联：[TDD-C 端裂变与商家招募](./TDD-C端裂变与商家招募.md)（招商入口那一半）·
[小程序上线指南](./小程序上线指南.md) §一（类目红线）
创建日期：2026-09-29

---

## 0. 起因与口径

### 0.1 入驻表单收的是**意向**，不是申请

`mch_entity_apply` 是意向表，`mch_entity` 是商家账户表，**确认后才建**——
`OpsServiceImpl` 审核通过那一支的注释原话是「审核通过才创建商家主体：
驳回的申请不该在库里留下一个僵尸商家」，`merchantAdminPort.activate(...)`
只在 `approved` 分支里调。

这一条不是新设计，是**现状**。写下来是因为它决定了两件事：
表单可以只问四项（资料在确认环节补），以及界面上该叫「入驻意向」而不是「入驻申请」。

### 0.2 四条拍板（2026-09-29）

| | 拍板 |
|---|---|
| 商家类型 | 行业下拉，**列出更多行业、允许手填**，目的是收集数据 |
| iOS 地址 | 给 TestFlight 公开链接（等 App Store Connect 开出来） |
| 修改边界 | 待审核可改，审核中锁定 |
| 运营触达 | 意向清单 + 提醒，考虑企业微信 |

### 0.3 六条查出来的事实

| | 事实 | 影响 |
|---|---|---|
| 1 | 小程序里入驻入口**开着**（`merchant.apply.mp-visible` 线上无此行 → 默认 true） | 改动真的会出现在小程序里，红线风险要算 |
| 2 | 线上**只开 2 个行业**（线下零售、居民生活服务），另 5 个 `enabled=0` 被 master-data 过滤 | 「更多行业」不是加数据，是要让未开放的也能选 |
| 3 | 后端**不校验** industry 是否启用 | 列更多行业不会被后端拒 |
| 4 | `REJECTED` 是终态，重提是新开一份单 | 「修改」只能对 PENDING；驳回后走新建 |
| 5 | 通知通道生产可用的**只有站内消息**（`NCH-INAPP`/PLATFORM），SMS/MAIL/WXSUB/PUSH 四个都是 `scope=TEST` | 提醒分两层上 |
| 6 | 企微 webhook **管理后台创建不了**（页面写明要在群聊里建），客户端未登录 | 要人点一次，不阻塞开发 |

---

## 1. 批 1：查看 / 修改 / 不能新建 + 下载引导（已实现）

### 1.1 状态 → 交互

| 状态 | 卡片右侧 | 点进去 | 动作 |
|---|---|---|---|
| 无单 | `›` | 新建表单 | — |
| `PENDING` | 待审核 | 查看态（回显四项） | 修改 |
| `REVIEWING` | 审核中 | 查看态 | 无按钮 + 说清为什么 |
| `REJECTED` | 已驳回，可修改重提 | 查看态 + 红色拒因 | 重新提交（**走新建**） |
| `APPROVED` | 已通过 | 查看态 | 无按钮 |

**此前不管有没有单都打开新建表单** —— 填完一遍，提交时才被
`uk_apply_active_owner` 拒掉。白填一次，且看不出为什么。

### 1.2 新端点

`POST /mp/merchant/apply/{applyNo}`：只在 `status=PENDING` 且是本人时放行。

- `REVIEWING` 锁 —— 运营正在看，改了之后他看的与库里存的不是同一份，而他不会知道
- `REJECTED` / `APPROVED` 也拒 —— 各自的理由见 `OpsService#updateApply` 的注释
- 三档都给 `APPLY_NOT_EDITABLE`（10467），不复用 `CONFLICT`（「资源冲突」店主读不懂）
- 不是本人的单当 `NOT_FOUND`，不是无权限 —— 后者等于确认这个单号有效

**逐字段 set，不用 `updateById`**：后者跳过 null，于是「把填错的推荐人删掉」
那句 set 根本不生成（[[mybatis-plus-skips-nulls]]）。而改意向最常见的动作
就是删掉一个填错的格子。

### 1.3 下载引导

字号 `txt-caption`(24rpx) → `txt-body`(28rpx) —— 它是提交完唯一要做的下一步，
此前和脚注一样小。按本机系统默认展示对应那一档（iOS 用户先看到安卓包会以为没有
iOS 版），另一档弱一档摆在下面。

**地址由后端下发**（`/mp/config/bootstrap` 的 `merchantApp.android` / `.ios`）：
写在端上就有两处真源，而这个项目已经错过一次 —— 商家端链接曾写死
`shop.example.com`，印了贴纸才发现。空的那一档不显示；两档都空时退回官网下载页
（那一页上两个平台都有）。**iOS 现在就是空的**，那一版还在苹果审核队列里。

---

## 2. 批 2–4（未开工）

| 批 | 内容 | 动库表 |
|---|---|---|
| 2 | 行业下拉：列全部 7 个（未开放的标「暂未开放」）+ 末项手填，新列 `mch_entity_apply.industry_note`；运营端接新字段 | 是 |
| 3 | 站内消息提醒运营（`NCH-INAPP` 已启用，不依赖外部配置） | 否 |
| 4 | 企微群机器人（`provider=WECOM`，凭据是一个 webhook URL）+ 下载二维码 | 是（通道行） |

**运营端的意向清单已经有了** —— 「入驻审核」（`/merchants`，`merchant:apply:audit`），
`apply-tab.tsx` 已显示行业与推荐人。批 2 要补的只是 `industry_note` 一列。

**称谓**：C 端已改成「我的入驻意向」；运营端菜单「入驻审核」→「商家意向」放批 2。

---

## 3. 三处对账（批 1）

### 3.1 设计 → 实现

| 设计条目 | 落点 |
|---|---|
| 查看态 / 三种状态分支 | `c-app/src/pages/me/index.vue`（`applyViewVisible` · `intentFields` · `applyEditable` / `applyRejected`） |
| 改意向端点 | `MpCatalogController#updateMerchantApply` · `OpsService#updateApply` + impl |
| 专门错误码 | `ErrorCode.APPLY_NOT_EDITABLE` + 三份 messages + `响应格式规范.md` §3 |
| 下载地址下发 | `ShopProperties.MerchantApp` · `BootstrapConfigService.MerchantApp` · yml 的 `merchant-app` |
| 端上契约 | `endpoints.ts` · `contract.ts` · `http.ts` · `mocks/group.ts` · `mocks/merchant.ts` · `gen-openapi.mjs` |

**偏差一处**：原方案让端上按域名拼下载链接，实现时改成后端下发 —— 理由同门店短链那次
（两处真源，已经错过一次）。

### 3.2 实现 → 需求

| AC | 测试 | 结果 |
|---|---|---|
| 待审核能改且真的落库 | `MerchantApplyEditFlowTest#pendingApplyCanBeEditedAndIsPersisted` | ✅ |
| 清空一个格子要真清掉 | `#clearingAFieldActuallyClearsIt` | ✅ |
| 审核中锁定 | `#reviewingApplyIsLocked` | ✅ 返回 10467 且库里没变 |
| 别人的单当不存在 | `#othersApplyLooksLikeNotFound` | ✅ 10404 |
| bootstrap 发下载地址 | `#bootstrapCarriesMerchantAppLinks` | ✅ 两档字段都在 |
| 四种状态的端上交互 | 浏览器实测（c-app mock，375×812） | ✅ 见 §3.3 |

### 3.3 端上实测（可证伪的那几条）

- 无单 → 表单 3 输入；提交 → 完成屏，下载标题 computed `fontSize = 14px`（28rpx，
  改之前是 12px），iOS 那行因地址为空**不显示**
- 再点卡片 → 标题「我的入驻意向」、**0 个 input**、五行回显、卡片右侧「待审核」
- 「修改」→ 表单预填 → 改店名 → 回查看态 + toast + **mock 库里 name 真的变了**，
  `applyNo` 与 `status` 没变
- 切 `REVIEWING` → 无修改按钮 + 「审核中不能修改，有问题请联系客服」
- 切 `REJECTED` → 拒因红色（`rgb(240,68,56)`）、动作变「重新提交」
- 「重新提交」→ 从被驳回那份预填 → 提交后是**新单号**、状态回 `PENDING`

**消融**：把 `updateApply` 的逐字段 set 换成 `updateById` →
`clearingAFieldActuallyClearsIt` 变红在「推荐人要真的没了」这一行，其余四条照过。
还原后复绿。

---

## 4. 边界

- **二维码不在批 1**：拍板是「先只做链接本身」
- **小程序类目红线**：现有注释写明「小程序里不能直接下载 APK（微信拦）」，
  所以只能复制链接转浏览器。下载区做显眼会提高审核注意度 ——
  批 4 加二维码时要挂一个开关，提审期间能一键收起
- **B 端合并后的跳转**：查看态底部留了动作位，现在指向下载引导

---

确认记录：2026-09-29 用户「开工」（批 1）
