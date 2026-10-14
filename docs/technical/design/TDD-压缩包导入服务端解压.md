# TDD-压缩包导入·服务端解压

状态：已实现
关联需求：本会话确认（2026-10-07）——小程序端从微信对话选 zip、上传后由服务端解压
关联：[PRD-商品快速录入-压缩包与文字识别](../../requirements/PRD-商品快速录入-压缩包与文字识别.md)、[TDD-B端小程序轻量运营并包](TDD-B端小程序轻量运营并包.md)
创建：2026-10-07 · 最后更新：2026-10-07

> **一句话**：App 端解压靠 `plus.zip`（原生能力），小程序没有。
> 小程序端改走 `uni.chooseMessageFile` 从微信对话选 zip → 整包上传 → **服务端解压**，
> 服务端顺手把图片过完校验落进媒体库，直接回带 URL 的清单；端上不再逐张上传。

## §0 对账一 · 需求 → 设计

| AC | 需求（一句话） | 落点 |
|---|---|---|
| AC1 | 小程序端能从微信对话里选 zip | `ports/zip-import.ts` 的 `MP-WEIXIN` 分支用 `uni.chooseMessageFile` |
| AC2 | zip 整包上传，服务端解压并返回文件清单（相对路径 + 宽高） | 新端点 `POST /biz/goods/zip-import` |
| AC3 | 图片在服务端就落进媒体库，端上拿到的是 URL，不再逐张上传 | 复用 `ImageProbe` 校验 + `MediaUploadService#store` |
| AC4 | txt 文本内容随清单一起回来（端上没有本地文件可读） | 响应里的 `texts` |
| AC5 | 分类/排序仍由现有纯逻辑做，两端同一套 | 复用 `classifyZipTree`（端上，已单测） |
| AC6 | **安全**：zip slip、解压炸弹、非图片字节都要挡 | 见 §2「安全」 |

**孤立项**：无。明确排除：App 端走法不变（`plus.zip` 本地解压那条保留，不回退到上传）。

## §1 现状与影响面

- App 端现状：`importZip()` 本地解压 → 回 `{root, files[], media}`，上层再逐张 `mUploadImage(本地路径)`。
- 小程序端现状：`ZIP_IMPORT_SUPPORTED` 为 false，入口直接不显示。
- 要复用（**不要另抄一份**）：
  - `ImageProbe.ALLOWED_EXT` 后缀白名单、`ImageProbe.looksLikeImage` **magic-number 校验**
    （`BizUploadController` 的注释记着实测：纯文本改名 `.png` 会以 `image/png` 落进公开桶）
  - `MediaUploadService#store`（记账+落盘三步，**刻意不用事务**，照抄会丢掉这一点）
  - key 的四层结构、`BizContext.requireMerchantNo()/requireStoreNo()`
- 不受影响：App 端链路、`mZipPlan`（模型分类）、`classifyZipTree`。

## §2 方案

### 契约变更
- 端点：**新增** `POST /biz/goods/zip-import`（multipart，字段 `file`）。
- 响应：
  ```json
  { "files": [ { "path": "主图/1.jpg", "width": 800, "height": 800, "url": "..." } ],
    "texts": { "说明.txt": "一段自由文字" } }
  ```
- 库表/迁移：**无**（图片走既有 `sys_media_asset`）。
- 权限码：无（沿用 `/biz` 既有鉴权 + 门店作用域）。
- ErrorCode：复用 `BAD_REQUEST`；超限另给 `ZIP_TOO_LARGE`。

### 安全（这个端点收的是用户上传的压缩包，下面每条都要有用例）

| 风险 | 做法 |
|---|---|
| **Zip Slip**（条目名 `../` 或绝对路径） | 条目名一律规范化后校验；带 `..`、以 `/` 开头、含 `\` 的**整条跳过**。服务端**不往磁盘解压**，而是流式读每个 entry 交给 `MediaUploadService`，从根上没有落点可穿越 |
| **解压炸弹** | 三道：单条目解压上限 5MB（与单图一致）、总解压上限 50MB、条目数上限 200。任一超限即中止并报 `ZIP_TOO_LARGE` |
| **伪装成图片的字节** | 每个图片条目都过 `ImageProbe.looksLikeImage`（magic number），不过就跳过 —— 与 `/biz/upload/image` 同一道闸 |
| **非图片非 txt** | 忽略（不报错）：商家打包时常夹带 `.DS_Store`、缩略图等 |
| **txt 过大** | 单个 txt 只取前 64KB，超出截断 |

### 模块设计
| 动作 | 路径 | 说明 |
|---|---|---|
| 新增 | `shop-channel/.../media/api/BizZipImportController.java` | 端点 + 解压 + 逐条校验落库。与 `BizUploadController` 同住 channel（它也只是「把字节存到某处并给回 URL」） |
| 修改 | `shop-base/.../common/ErrorCode.java` | `ZIP_TOO_LARGE` |
| 修改 | 后端三语 i18n | `err.zip.too_large` |
| 修改 | `b-app/src/ports/zip-import.ts` | 加 `MP-WEIXIN` 分支；`ZipEntry` 加可选 `url`；`readTextFile` 在 mp 端读内存里的 `texts` |
| 修改 | `b-app/src/pages/goods-edit/photos.ts` | 条目**有 url 就直接用**，没有才 `mUploadImage`（App 端行为不变） |
| 修改 | `b-app/src/api/endpoints.ts` + `http.ts` | 登记 `mZipImport` |

## §5 对账三 · 实现 → 需求（测试）
用例都加在 `MediaUploadFlowTest`（复用它已有的「入驻→审核→换 B 端令牌」脚手架，近 30 行，不该抄一份）。

| AC | 测试 | 跑过 | 消融 |
|---|---|---|---|
| AC2/AC3/AC4 | `zipImportStoresImagesAndReturnsTexts`：两图一 txt → 回 2 条带 url 的清单（路径原样、宽高对）+ txt 内容 | ✅ `Tests run: 9, Failures: 0` | — |
| AC6 | `zipImportSkipsPathTraversalEntries`：`../evil.jpg` 与 `/abs.jpg` 被跳过，只剩干净那张 | ✅ 同上 | ✅ 撤掉 `unsafe()` → **这条变红** |
| AC6 | `zipImportSkipsNonImageBytes`：纯文本改名 `.png` 不落库 | ✅ 同上 | ✅ 撤掉 magic 校验 → **这条变红** |
| AC6 | `zipImportRejectsTooManyEntries`：205 个条目 → 非 0 业务码 | ✅ 同上 | — |

**消融是两道一起做的**：同时废掉 `unsafe()` 与 magic 校验 → 正好那两条变红、其余七条仍绿（说明用例各自钉住各自那道闸，不是连坐）。还原后 9/9 回绿。

**端分叉也验了**（条件编译最容易串味的地方）：
- 小程序产物 `pkg-biz/ports/zip-import.js` 里有 `chooseMessageFile`
- App 产物里 `chooseMessageFile` **0 处**、仍走 `plus.zip`

## §6 对账二 · 设计 → 实现

| 差异 | 说明 |
|---|---|
| TDD 没列、实际改了：`api/mocks/product.ts` | `MerchantApi` 加了方法，mock 不补就 vue-tsc 红（类型检查当场抓到） |
| TDD 没列、实际改了：`api/requests.ts` / `contract.ts` | `ZipImported` 契约类型要有地方放 |
| 设计如此、实现一致 | 服务端**不往磁盘解压**：每个 entry 读进内存直接交给 `MediaUploadService`，落点由自己拼的 key 决定 —— zip slip 从根上没有落点可穿越 |
