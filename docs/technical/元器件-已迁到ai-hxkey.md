# 元器件 · 已迁到独立项目 ai-hxkey

> 2026-09-30 · 状态：**已迁出**（来源提交 `383494c1b`；方案见 [TDD-元器件-独立成项目ai-hxkey](./TDD-元器件-独立成项目ai-hxkey.md)）

电子元器件（料号库、供应商库存、询报价、元器件小程序）已迁到独立项目 **`~/work/ai/ai-hxkey`**。
迁出前的提交历史仍在本仓库：`git log -- backend/elec elec-app`。

## 搬走了什么

| 原位置（本仓库） | 新位置（ai-hxkey） |
|---|---|
| `backend/elec/elec-core`、`backend/elec/elec-svc` | `backend/elec-core`、`backend/elec-svc` |
| `elec-app/` | `app/` |
| `packages/shared/src/types/elec.ts` | `app/src/types/elec.ts` |
| 元器件的 TDD、PRD、数据库设计、需求梳理、表清单、ER 图、上传流程图 | `docs/` 下同名 |
| `prototypes/elec-rfq.html`、`elec-app-shell.html` | `prototypes/` |
| `scripts/gen-elec-erd.mjs`、元器件 H2 表结构生成 | `scripts/` |
| `deploy/tencent/systemd/ai-shop-elec.service`、`deploy-backend.sh` 的 elec-svc 分支 | `deploy/systemd/`、`scripts/deploy.sh` |

## 留在本仓库的（运营端与契约）

- `backend/elec/elec-api`：主系统给元器件的**内部接口契约**。ai-hxkey 从本机 `~/.m2` 引它 —— 改它等于改两个仓库之间的契约
- `InternalElecEndpoint`：认运营令牌、通知（后续按迁移方案第 5 步改为发短信 / 换 openid / 按 openid 发订阅消息）
- 运营端：ops-web 的元器件四页、`V370` 菜单、`V371` 岗位授权（[TDD-元器件-运营端岗位授权](./TDD-元器件-运营端岗位授权.md)）、`Perms` 的六个码
- `ErrorCode` 里的 `9xxxx`：迁移方案第 3 步后删除
- c-app 的并包脚本 `c-app/scripts/with-elec.mjs`：源路径改指 `../ai-hxkey/app/src`，测试期仍并进虹选打包
- nginx `location ^~ /elec/`；生产服务名 `ai-shop-elec`、库 `ai_shop_elec` 不改名
