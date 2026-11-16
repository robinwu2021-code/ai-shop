# 台湾进件材料识别交接

状态：2026-11-16，识别代码已推送对接分支；本次交接密钥已通过 uu SSH 同步。尚未合并 ai-shop 主线、部署识别版本或完成 UME166 登录后验收。

## 代码位置

| 仓库 | 分支 | 功能提交 | 内容 |
|---|---|---|---|
| ai-shop | `codex/openai-onboarding-20261116` | `e5d0702b7`、`c7070392f` | OpenAI 适配器、图片/PDF 读取、OPS 识别接口、权限登记、配置及测试 |
| NearPay | `codex/openai-onboarding-20261116` | `5a2616c9` | 运营材料表单的识别、核对、勾选回填；同提交已进入 `develop/s1` |

只对接识别逻辑时，拉取 ai-shop 即可。识别服务不依赖 NearPay 支付渠道；NearPay 是现有进件材料表单的调用方。

```bash
git fetch origin
git switch --track origin/codex/openai-onboarding-20261116
```

若本地分支已存在，切换该分支后执行 `git pull --ff-only`。

## uu 远端密钥

- SSH 别名：`uu-neargo-mac`，用户 `neargo2026`。
- 远端文件：`/Users/neargo2026/.config/ai-shop/ume166/openai-vision.env`。
- 文件权限 `0600`，项目配置目录 `0700`；已回读确认与本地待同步值一致。SSH 通过标准输入传送，密钥未进入命令参数、终端输出或 Git。
- 本机来源：`/Users/robin/.config/ai-shop/ume166/openai-vision.env`。
- 文件包含 `SHOP_AI_VISION_OPENAI_API_KEY` 以及 provider、独立启用开关、模型和超时配置。模型为 `gpt-4.1-mini`，超时为 60 秒。

在 uu 机器启动服务的同一终端加载：

```bash
set -a
. /Users/neargo2026/.config/ai-shop/ume166/openai-vision.env
set +a
# 随后在同一终端按项目原有方式启动 ops 服务。
```

IDE、Docker 或服务管理器须把该文件配置为进程环境来源；文件存在不代表运行进程已加载。不要把 env 文件复制进仓库或前端构建配置。非秘密配置模板见 [shop-app.env.example](../../../deploy/env-templates/shop-app.env.example)。

UME166 另有服务端环境文件 `/data0/app/ai-shop/ai-shop.env`，密钥此前已同步；本次 uu 同步没有重启任何服务，也没有激活 UME166 的识别实现。

## 接口对接

- 宿主 OPS：`POST /ops/onboarding-documents/recognize`，multipart 字段 `file`、`documentType`。
- 在现有运营网站经宿主代理访问时，路径为 `/ops-shop/ops/onboarding-documents/recognize`。
- 使用现有运营 Bearer 会话；权限为 `merchant:admission:update` 或 `merchant:apply:onbehalf`。不要把 OpenAI API Key 传给浏览器或业务接口。
- 服务需启用 `ops` profile，实例地区为系统配置 `TW`，不接受请求切换地区。
- 输入支持 PNG/JPEG/PDF，最大 12MiB；图片沿用缩放和重编码，PDF 在内存校验，不上传公开存储。

| documentType | 材料 | 返回字段 |
|---|---|---|
| `REGISTRATION` | 公司登记 | name、taxNo、registeredAddress、incorporationDate、capitalAmount、representativeName |
| `OWNER_FRONT` | 身份证正面 | representativeName、identityNo、birthDate、issueDate、issuePlace、idIssueType、expiryDate |
| `OWNER_BACK` | 身份证背面 | identityAddress、expiryDate |
| `BANK_COVER` | 银行证明 | bankCode、branchCode、accountName、bankAccount |

沿用宿主统一响应封装，业务数据为 `recognized`、`documentType`、`fields`、`confidence`。配置缺失、模型不可用或无有效识别结果时 `recognized=false`，不能当作验证通过。鉴权、地区和非法输入仍返回相应业务错误。

识别只提供人工核对建议，不保存附件、不修改商户、不审核、不送审、不派发渠道。调用方须让用户明确选择后回填，沿原保存流程落库。编号、账号保留前导零；资本额只取明确的新台币实收资本。NearPay 回填时，台湾身份证发证日转换为民国七位，出生/设立日期为公历八位；渠道字典项需人工选择。不要持久缓存识别原文。

## 已验证与待办

- 真实 OpenAI 调用通过：合成公司登记、身份证正面、银行证明各 PNG/PDF；另有注册资本与实收资本区分的 PNG/PDF 复验。没有用真实证件做准确性验收。
- 服务端针对性测试 7 项通过；运营端新增及相关测试 15 项、类型检查、保留线上既有功能的兼容构建通过；独立代码审查问题已修复并复核。
- 推送门禁的生成检查通过，宿主运营端 1273 项断言无失败；后端全量门禁未跑完，按用户“直接推送”指令中止并跳过。本交接不声称全量门禁通过。
- 下一步：uu 加载私有 env 后对接接口；合并主线与部署时保留 UME166 现有增量版本，再通过运营端验证上传、识别、显式回填和结果清理。暂不派发真实渠道。

详细设计及验证依据见 [TDD-进件材料OpenAI识别](../../technical/design/TDD-进件材料OpenAI识别.md) 与 [本地脱敏验证记录](../../technical/evidence/openai-onboarding-20261116/local-verification.json)。
