# 门店创建审核 · 166 线上完整测试 · 2026-11-12

环境：UME 166（`ai-shop-api` 18082 / `ai-shop-ops` 18081），批次 `11120924`，全部为 TEST ONLY 数据。
店主侧调用在 166 本机执行，测试验证码与店主令牌不出主机，测完已删除。运营侧为票据登录的 `ST-UME-CONFIG`（建单）与 `ST-UME-REVIEW1`（审核）。

**结论：37 项检查，35 项符合预期，2 项不符合，原因是同一个缺陷（§三.1）。** 主流程「自助开店 / 驳回重提 / 代客建店 → 审核 → 主体启用 → 买家可见」全部走通。

## 一、准备

| 事项 | 处理 |
|---|---|
| 建店开关 | `COMMERCE_STORE_CREATION_ENABLED=true` 写入 `ai-shop-ops` 使用的 `/data0/app/ai-shop/ai-shop.env`（备份 `ai-shop.env.before-storecreation-*`）。此前只在重建的容器里打开，按原配置发布会回到关闭 |
| 测试运营权限（用户决定：长期功能点，替代临时超管） | `sys_role_point` 新增 4 行，`created_by='STORE_REVIEW_TEST'`：`NP_IT_CONFIG` + `OPS_MERCHANT__TAB_ON_BEHALF`（`merchant:apply:onbehalf`）、`OPS_SYSTEM__TAB_TESTPHONE`（`system:testphone:read`）、`ACT__SYSTEM_TESTPHONE_UPDATE`；`NP_IT_REVIEW` + `OPS_MERCHANT`（`merchant:apply:audit`）。建单与审核仍分属两个角色，约 30 秒后生效 |
| 测试身份 | 白名单邮箱 id 5/6/7（`storetest-{np,ent,ob}-11120924@test.umeshop.ai`），测后已停用 |

撤销长期功能点：

```sql
DELETE FROM ai_shop_ume.sys_role_point WHERE created_by='STORE_REVIEW_TEST';
```

## 二、测试矩阵

### A. 商户自助开店 · 自然人 · 零售

主体 `M261112SMSE46Y54G`，门店 `ST261112ZYT7BVJTG1`，申请 `MA261112K4GDSQTYWC`。

| 检查 | 结果 |
|---|---|
| 快速开店：`POST /biz/merchant/quick-start`（TW / RETAIL / RETAIL_CVS）建出 `PENDING_LICENSE` 占位主体 | ✅ |
| 证照上传：`/biz/upload/image?bizType=QUAL` | ✅ |
| 提交时缺 `X-Store-No` 被拒（强类型建店下 70006） | ✅ |
| 自然人 + `ONLINE` 业态应被拒 | ❌ **被受理**（§三.1） |
| 正式提交（RETAIL） | ❌ 409：上一条已占用「一人一份进行中申请」。因此**最终审核通过的是那张 ONLINE 业态单** |
| 有进行中申请时重复提交被拒（409） | ✅ |
| 商户查看申请：PENDING，未签协议 | ✅ |
| 商户签协议：`/biz/merchant/agreement/accept` | ✅ |
| 运营待审队列 `/ops/merchant/apply/search` 可见 | ✅ |
| REVIEW1 审核通过；申请变 APPROVED；主体原地变 ACTIVE，门店号不变 | ✅ |
| 买家端 `/mp/store/nearby` 可见 | ✅ |

### B. 商户自助开店 · 企业 · 驳回后重提

主体 `M261112WXMP8F8P3J`，门店 `ST261112RSZMTVPZ1Y`。驳回单 `MA26111220915CYB0B`，重提单 `MA261112R45F3F6ZEQ`。

| 检查 | 结果 |
|---|---|
| 企业缺 `BUSINESS_LICENSE` 被拒（10400） | ✅ |
| 带营业执照提交 | ✅ |
| 驳回不填原因被拒（10400） | ✅ |
| REVIEW1 驳回；商户看到 REJECTED、驳回原因，资质保留可回填 | ✅ |
| 驳回后主体仍是 `PENDING_LICENSE` | ✅ |
| 重提生成新申请单（PENDING） | ✅ |
| 已驳回的旧单不能再审核通过（20004） | ✅ |
| 第二次审核通过 → ACTIVE，门店号不变，买家可见 | ✅ |

### D. 运营代客建店 · 企业

申请 `MA2611124NKDCPW1H5`，店主 `U261112NXMSMHXXHC`，主体 `M261112MWJ4GK3318`，门店 `ST261112XKX3ET3J8D`。

| 检查 | 结果 |
|---|---|
| CONFIG 代客建单（企业 + 营业执照 + 门店资料，TW） | ✅ |
| 建单人不能审核自己的单（10403） | ✅ 本轮由权限拦截（`NP_IT_CONFIG` 无审核权）。制审分离规则本身已在上午闭环中验证：CONFIG 持超管时自审同样被拒 10403 |
| REVIEW1 审核通过，主体 ACTIVE | ✅ |
| 店主看到 `agreementPending=true` → 补签协议 → 提示消失 | ✅ |

## 三、发现

1. **主体与业态门禁失效（缺陷：数据 + 代码）**
   - 数据：166 的 `sys_legal_form` 只有 1 行 `ENTERPRISE`（`created_by=TW166-TEST-FIXTURE`，2026-11-11）。种子里的 `NATURAL_PERSON`、`INDIVIDUAL` 缺失；`sys_industry` 也只有 `CATERING`。`/common/master-data` 因此只返回 1 种主体、1 个行业。
   - 代码：`OpsServiceImpl.requireSubjectAllowedByIndustry` 在 `canonicalSubject` 返回 null（主数据里没有该主体）时直接放行；`industry` 也不校验是否存在。主数据缺行时，门禁「失效放行」而非拒绝。
   - 后果：自然人 + 线上业态这一被禁止的组合在 166 上通过了入驻审核（A 的主体）。
   - 建议：补齐 166 主数据；代码改为未知主体、未知行业直接拒绝（契约变更，需按 TDD 走）。
2. **协议门禁**（已知待决，本次补充证据）：审核不读 `agreed_at`。
   - 代客单：未签即通过（D），店主侧会提示补签。
   - **自助单：同样未签即通过（B），且 `agreementPending` 只对代客单显示，自助商户永远不会被提示签协议。** 这比代客单更隐蔽。
3. **B 端 App 联系电话校验只认大陆手机号**（`packages/shared/src/utils/validate.ts` 的 `isPhone`）：台湾商户从 App 界面无法提交入驻申请。服务端不校验，本测试直接调用接口提交。
4. **B 端快速开店只传店名**（`b-app/src/pages/home/index.vue:407` 只发 `{storeName}`；`b-app/src/pages` 下没有任何页面传 `businessTypeCode`）。强类型建店开启后 `businessTypeCode` 必填，所以 166 上 App 首页的「快速开店」会失败，商户无法从 App 自助开第一家店。本测试直接调用接口补齐了业态参数。此结论来自读代码，未在真机上复现。

## 四、清理

- 测试身份 id 5/6/7 已停用；166 本机店主令牌文件与测试脚本已删除。
- 三个 TEST ONLY 主体（A/B/D）保留，作为测试样本。
- 长期功能点保留（用户决定），撤销语句见 §一。
