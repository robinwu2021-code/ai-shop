# 注册开店、行业 Plan、商城与门店端访问权益：API 清单

日期：2026-11-03。状态：建议接口合同，除明确标为“已有”的端点外，均不表示已经实现。

上游：[需求](../requirements/注册开店与行业Plan商城-需求.md)、[总体方案](../superpowers/specs/2026-11-03-industry-plan-mall-design.md)、[数据设计](../technical/design/门店行业Plan商城-数据库设计.md)。

叠加策略已由用户确认：同门店同 code 的所有来源有限时长累计。报价、订单和交付记录 `stackingPolicyVersion=END_CODE_ACCUMULATE_V1`，返回累计结果与来源时间片；不存在跨来源重叠消耗的候选规则。

## 1. 通用合同

- 沿用项目响应 `{code,msg,data}`、业务号和分页形态；认证失败按现有 401 机制，权益不足／过期属于业务失败，不清账号 token。新增符号错误名需在实施时分配不冲突的数值并同步 OpenAPI、共享类型和国际化。
- B 端门店路径是资源标识，服务端必须与当前用户可访问门店匹配；不得仅信任 `X-Store-No`。原有 header 在兼容接口仍可用，路径与 header 同时存在且不一致时拒绝。
- 以下“采购”权限是岗位 RBAC，建议 `biz:commerce:purchase`；“建店”沿用主体级 `biz:store:admin`。具体新权限码在实施时与 `BizPerms` 守卫对齐。
- 运营权限按读取／编辑／发布／退款审核／人工授予分离；下文为建议码，最终纳入现有 `Perms` 和角色功能点真源，不是商业权益 code。
- 金额字段以 `Minor` 结尾，整数并附币种；时间 epoch 毫秒，结束不含端点。
- POST 创建订单、提交支付、申请退款、赠送、撤销、发货、重试等写入要求 `Idempotency-Key`；同键同参数返回同一结果，同键异参数返回冲突。配置更新／发布要求 `expectedVersion`。
- Plan 和 SKU 已发布版本不可变；业务端访问 code 不等于可任意编辑的 API 白名单。

## 2. 身份、开店与门店选择

| 方法 | 路径 | 状态／权限 | 入参与返回要点 |
|---|---|---|---|
| POST | `/biz/auth/otp/send` | 已有，匿名限流 | phone；发送验证码 |
| POST | `/biz/auth/login` | 已有，扩展返回 | grantType/principal/credential/agreed；账号 token、门店摘要、nextAction |
| GET | `/biz/auth/context` | 新增，账号登录 | 门店列表、身份、默认门店、是否可建店；无店也可调用 |
| GET | `/biz/business-types` | 新增，账号登录 | 可创建的经营类型、说明、Plan 目录版本 |
| GET | `/biz/business-types/{typeCode}/plans` | 新增，账号登录 | 三档比较、Plan 版本、可售端清单、期限 SKU、价格与可售状态 |
| POST | `/biz/merchant/quick-start` | 已有，扩展 | 新增 businessTypeCode、协议版本、可选 selectedSkuNo；返回 storeNo、FREE 权益、购买入口 |
| POST | `/biz/store/create` | 已有，扩展，建店 | 同主体新增店，经营类型必填；旧主体额度仍按兼容策略校验 |
| GET | `/biz/store/list` | 已有，扩展 | 每店 businessTypeCode、身份、付费状态、可进入端摘要 |
| GET | `/biz/stores/{storeNo}/context` | 新增，成员 | 门店经营类型、FREE／当前 Plan、端权益、治理／资质状态 |
| POST | `/biz/stores/{storeNo}/end-sessions` | 新增，成员 | endCode、可信 clientId／设备证明；检查后返回端上下文或续费引导 |
| GET | `/biz/stores/{storeNo}/end-access` | 新增，成员 | 全部可用端、原因、连续有效期、下一生效时间；端列表不能包含越权运营端 |
| POST | `/biz/auth/logout` | 建议补齐 B 端出口 | 撤销当前 B 端账号与其端上下文；复用现有 token 撤销设施 |

登录 `nextAction` 建议：CREATE_STORE／SELECT_STORE／ENTER_STORE；某端到期不改变账号认证结果。兼容现有登录响应时新增可选字段，客户端迁移后再收敛旧 profile。

建店输入建议：

```json
{
  "storeName": "示例餐饮店",
  "address": "示例地址",
  "businessTypeCode": "FOOD",
  "selectedSkuNo": "FOOD_PRO_365D",
  "agreementVersion": "2026-11"
}
```

selectedSkuNo 只表示用户意向，建店接口不扣款、不假定付费权益已生效；必须验证其适配行业。返回真实创建的门店，再走商城报价。

## 3. B 端订阅与商城

| 方法 | 路径 | 权限 | 入参与返回要点 |
|---|---|---|---|
| GET | `/biz/stores/{storeNo}/subscription` | 成员 | 当前／未来 Plan 周期、FREE、到期、升级／续费可选项；金额明细限采购权限 |
| GET | `/biz/stores/{storeNo}/entitlements` | 成员 | 端权益 code、可用状态、合计连续到期日、累计未用时长、来源摘要 |
| GET | `/biz/stores/{storeNo}/entitlements/{code}/sources` | 采购／管理 | 每笔来源的原授予、已用／未用时长、Plan／端包／赠送、订单和时间片 |
| GET | `/biz/stores/{storeNo}/mall/categories` | 成员 | PLAN／END_ACCESS／BUNDLE／HARDWARE 分类 |
| GET | `/biz/stores/{storeNo}/mall/products` | 成员 | category、keyword、分页；按门店经营类型和可售状态筛选 |
| GET | `/biz/stores/{storeNo}/mall/products/{productNo}` | 成员 | SKU、价格、期限、可进入端、套餐组件、同 code 累计及排期规则、退款说明 |
| POST | `/biz/stores/{storeNo}/mall/quotes` | 采购 | intent、SKU／数量；服务端报价，升级折价和余额分配一并返回 |
| POST | `/biz/stores/{storeNo}/mall/orders` | 采购 | quoteNo、收货地址；数字订单占用门店排期，另按需预留余额／库存 |
| GET | `/biz/stores/{storeNo}/mall/orders` | 采购／订单查看 | 分页和支付／交付／退款状态筛选 |
| GET | `/biz/stores/{storeNo}/mall/orders/{orderNo}` | 采购／订单查看 | 成交快照、应付实付、组件、权益交付、硬件物流 |
| POST | `/biz/stores/{storeNo}/mall/orders/{orderNo}/payment-attempts` | 采购 | 渠道、客户端支付场景；返回渠道支付参数或零元结算状态 |
| POST | `/biz/stores/{storeNo}/mall/orders/{orderNo}/payment-refresh` | 采购／订单查看 | 受控触发服务端查单；不信任客户端传 paid=true |
| POST | `/biz/stores/{storeNo}/mall/orders/{orderNo}/cancel` | 采购 | 原因；先关支付并查明结果，未知时返回处理中 |
| GET | `/biz/stores/{storeNo}/mall/orders/{orderNo}/shipments` | 采购／订单查看 | 包裹与组件、物流状态 |
| POST | `/biz/stores/{storeNo}/mall/orders/{orderNo}/refunds` | 采购 | 来源组件、数量、原因；不能任填退款金额跳过来源校验 |
| GET | `/biz/stores/{storeNo}/mall/refunds/{refundNo}` | 采购／订单查看 | 审核、资金退款与权益调整状态 |
| GET | `/biz/stores/{storeNo}/subscription-credits` | 采购 | 可用／预留抵扣余额、币种、使用范围 |
| GET | `/biz/stores/{storeNo}/subscription-credits/ledger` | 采购 | 转换、消费、恢复、调整流水与来源订单 |

续费与升级复用报价／订单合同，以 `intent=RENEW/UPGRADE` 区分，不另建绕过商城的“直接升级成功”接口。

### 3.1 报价请求及主要响应

```json
{
  "intent": "UPGRADE",
  "items": [{"skuNo": "FOOD_CHAIN_365D", "quantity": 1}]
}
```

响应 data 示例（金额仅示意，生产时间返回毫秒整数）：

```json
{
  "quoteNo": "MQ_001",
  "storeNo": "ST_001",
  "businessTypeCode": "FOOD",
  "intent": "UPGRADE",
  "currency": "CNY",
  "priceMinor": 69900,
  "newConversionMinor": 20000,
  "existingCreditAvailableMinor": 0,
  "creditUsedMinor": 20000,
  "creditBalanceAfterMinor": 0,
  "cashDueMinor": 49900,
  "subscriptionVersion": 7,
  "creditAccountVersion": 3,
  "valuationAt": 1793664000000,
  "expiresAt": 1793664900000,
  "durationDays": 365,
  "activationPolicy": "ON_FULFILLMENT",
  "stackingPolicyVersion": "END_CODE_ACCUMULATE_V1",
  "conversionSources": [
    {"periodNo": "SP_OLD", "eligibleBasisMinor": 36500, "creditMinor": 20000}
  ]
}
```

订单创建只提交 quoteNo 和必要收货资料；金额、门店归属、权益 code、折价比例均不得由前端指定。客户端展示倒计时不代替服务端有效期判断。

混合套餐报价须额外返回 `creditEligibleMinor` 和逐组件、逐周期分摊；只有新 Plan 组件可使用订阅抵扣，不能将硬件整单一起抵扣。Plan、独立端包及数字套餐订单共用门店数字结算占用，原单全部数字来源和排期交付完成或确认无付款关闭前，不能另开数字权益订单；纯硬件单可独立处理。

### 3.2 跨来源加购的排期预览

报价额外返回每个受影响 code 的 schedulePreview。下例为 POS 原到期 2026-12-01 00:00、加购后到期 2026-12-31 00:00（Asia/Shanghai）；确切日期以服务端排期为准，首购以实际交付时刻起算。

```json
{
  "stackingPolicyVersion": "END_CODE_ACCUMULATE_V1",
  "schedulePreview": [
    {
      "entitlementCode": "END_APP_POS",
      "previousEffectiveUntil": 1796054400000,
      "addedDurationMs": 2592000000,
      "estimatedEffectiveUntil": 1798646400000,
      "preservedStandaloneRemainingMs": 0,
      "estimated": true
    }
  ]
}
```

升级预览须区分“旧 Plan 转换抵扣”和“端包未用时长保留”，不能将被转换的旧 Plan 剩余时间也作为新增时长再次累加。端包被新 Plan 插入时，sources 接口返回同来源的多个 segments，并保持原授予时长不变；不得用来源首末日期差代表已用量。

### 3.3 业务端检查响应

```json
{
  "storeNo": "ST_001",
  "businessTypeCode": "FOOD",
  "entitlementVersion": 8,
  "ends": [
    {"endCode": "BIZ_APP", "entitlementCode": "END_BIZ_APP", "allowed": true, "effectiveUntil": null},
    {"endCode": "APP_POS", "entitlementCode": "END_APP_POS", "allowed": false,
     "reason": "EXPIRED", "nextAction": "RENEW", "nextStartAt": null}
  ]
}
```

永久资格的 null 明确表示无结束时间；未知结果不能也用 null 伪装为永久。账号无效走认证失败，不返回 allowed=false 混淆。

## 4. 平台运营：经营类型、业务端与 Plan

以下权限建议前缀为 `commerce`，需要落实到现有运营 RBAC。读接口不要求商家门店商业权益。

| 方法 | 路径 | 建议运营权限 | 用途 |
|---|---|---|---|
| GET | `/ops/business-types` | `commerce:catalog:read` | 经营类型与三档发布状态 |
| POST | `/ops/business-types` | `commerce:catalog:edit` | 创建未启用类型草稿并初始化三档稳定定义；不能用enabled=true绕过发布 |
| PUT | `/ops/business-types/{typeCode}` | 按字段校验 `commerce:catalog:edit/publish` | 名称／排序需edit，enabled变化需publish，同时修改须同时满足；不改稳定编码 |
| GET | `/ops/business-ends` | `commerce:catalog:read` | 整端目录、唯一权益 code、适用类型与验收状态 |
| POST | `/ops/business-ends` | `commerce:catalog:edit` | 登记端及 code；不能直接发布未验收端 |
| PUT | `/ops/business-ends/{endCode}` | `commerce:catalog:edit` | 编辑可变展示信息；已用 code 禁止改名重绑 |
| GET | `/ops/industry-plans` | `commerce:catalog:read` | 按类型列三档、当前版本与订阅数 |
| POST | `/ops/industry-plans/{planNo}/versions` | `commerce:catalog:edit` | 从当前版本复制草稿，编辑名称和整端权益组合 |
| PUT | `/ops/industry-plan-versions/{versionNo}` | `commerce:catalog:edit` | 编辑草稿，expectedVersion |
| GET | `/ops/industry-plan-versions/{versionNo}` | `commerce:catalog:read` | 版本详情、端列表、关联 SKU 与变更差异 |
| POST | `/ops/business-types/{typeCode}/plan-publications` | `commerce:catalog:publish` | 发布一个行业的三档版本与关联 SKU 指针，整体校验 |
| POST | `/ops/industry-plans/{planNo}/off-sale` | `commerce:catalog:publish` | 停售新购，已购继续有效；FREE 不可停用 |

发布输入包含 threePlanVersionNos、关联 skuVersionNos 和 expectedCatalogVersion；发布器校验三档唯一、FREE 必需端、高档超集、端验收、经营类型、币种和 SKU 内容一致。

首次行业发布允许发布包包含相互匹配的 Plan 草稿与推荐 SKU 草稿，服务端在同一事务内发布版本并切换当前指针；包内不要求推荐 SKU 事先单独发布。独立 SKU 发布仍要求所引用 Plan 版本已发布。发布失败不得留下只发布一部分的行业目录，避免首次上线的循环依赖。

业务端“VERIFIED”不能仅凭运营输入声明。须校验接入客户端、路由策略和验收记录引用；正式验收和部署流程仍由项目既有发布机制负责。

## 5. 平台运营：商品、订单、物流和退款

| 方法 | 路径 | 建议运营权限 | 用途 |
|---|---|---|---|
| GET / POST | `/ops/biz-mall/products` | `commerce:catalog:read/edit` | 商品列表／创建草稿 |
| PUT | `/ops/biz-mall/products/{productNo}` | `commerce:catalog:edit` | 商品图文和范围 |
| POST | `/ops/biz-mall/products/{productNo}/skus` | `commerce:catalog:edit` | 新规格稳定身份 |
| POST | `/ops/biz-mall/skus/{skuNo}/versions` | `commerce:catalog:edit` | 新版本价格、期限、组件、分摊、退款政策 |
| PUT | `/ops/biz-mall/sku-versions/{versionNo}` | `commerce:catalog:edit` | 编辑草稿 |
| GET | `/ops/biz-mall/sku-versions/{versionNo}` | `commerce:catalog:read` | 可售内容与分摊预览 |
| POST | `/ops/biz-mall/sku-versions/{versionNo}/publish` | `commerce:catalog:publish` | 校验后发布；关联 Plan 版本必须已发布 |
| POST | `/ops/biz-mall/skus/{skuNo}/off-sale` | `commerce:catalog:publish` | 停售、原因、是否按紧急政策关闭未付款单 |
| GET | `/ops/biz-mall/stock` | `commerce:catalog:read` | 平台仓库存与预占；调用库存权威合同 |
| POST | `/ops/biz-mall/stock-adjustments` | `commerce:stock:adjust` | 受控库存调整及原因，不直接改订单发货数 |
| GET | `/ops/biz-mall/orders` | `commerce:order:read` | 按门店／类型／支付／交付筛选 |
| GET | `/ops/biz-mall/orders/{orderNo}` | `commerce:order:read` | 订单、支付、分摊、权益／余额来源、交付明细 |
| POST | `/ops/biz-mall/orders/{orderNo}/shipments` | `commerce:shipment:manage` | 组件数量、包裹、物流，防超量发货 |
| POST | `/ops/biz-mall/shipments/{shipmentNo}/tracking-refresh` | `commerce:shipment:manage` | 刷新物流事实，不直接改签收事实 |
| GET | `/ops/biz-mall/refunds` | `commerce:refund:read` | 待审核、未知和失败退款 |
| POST | `/ops/biz-mall/refunds/{refundNo}/review` | `commerce:refund:review` | 批准／拒绝、来源分配、现金与余额恢复明细、原因 |
| POST | `/ops/biz-mall/refunds/{refundNo}/refresh` | `commerce:refund:review` | 查原退款结果并推进协调，不重复创建退款 |
| POST | `/ops/biz-mall/fulfillment-tasks/{taskNo}/retry` | `commerce:fulfillment:retry` | 重放原任务，不能创建额外权益 |

## 6. 平台运营：门店权益与迁移

| 方法 | 路径 | 建议运营权限 | 用途 |
|---|---|---|---|
| GET | `/ops/store-subscriptions` | `commerce:subscription:read` | 行业、Plan、到期、异常交付看板 |
| GET | `/ops/stores/{storeNo}/subscription` | `commerce:subscription:read` | 订阅和未来周期、旧合同保护 |
| GET | `/ops/stores/{storeNo}/end-entitlements` | `commerce:subscription:read` | 整端合计到期、来源时长、时间片及重排历史 |
| POST | `/ops/stores/{storeNo}/subscription-grants` | `commerce:subscription:grant` | 按 Plan 或独立端包赠送，期限、原因、来源；基数 0 |
| POST | `/ops/stores/{storeNo}/subscription-periods/{periodNo}/revoke` | `commerce:subscription:revoke` | 撤销来源、说明影响；付费退款必须另走协调 |
| GET | `/ops/stores/{storeNo}/subscription-credits` | `commerce:subscription:read` | 余额、批次与来源账本 |
| POST | `/ops/stores/{storeNo}/subscription-credit-adjustments` | `commerce:credit:adjust` | 凭证和审核原因必填，不可直接写 balance |
| POST | `/ops/stores/{storeNo}/business-type-change-previews` | `commerce:subscription:read` | 跨行业变更影响预览；首期不提供自助确认变更 |
| GET | `/ops/subscription-migrations/{batchNo}` | `commerce:migration:read` | 旧合同到门店映射、未知类型和金额差异 |
| POST | `/ops/subscription-migrations/{batchNo}/validate` | `commerce:migration:manage` | 校验端资格、旧承诺和分摊守恒 |
| POST | `/ops/subscription-migrations/{batchNo}/cutover` | `commerce:migration:manage` | 只切已通过校验的门店，幂等记录 |

人工赠送不等于人工修改成交金额；有历史付款证据的商业修复走抵扣调整／迁移凭证，不伪造一张“已支付”的商城单。

## 7. 内部合同与后台任务

下列是逻辑内部合同，优先用现有服务 SPI／支付通知设施实现；若跨进程才落为内网 API，不暴露给客户端。

| 合同 | 输入／输出 | 约束 |
|---|---|---|
| `CommercePaymentConfirmed` | payOrderNo、金额币种、收款方、支付时间、事件号 | 验证支付域签名或内网身份、inbox 幂等 |
| `CommerceRefundConfirmed` | payRefundNo、原支付单、现金金额、最终状态 | 原单关联、重复补偿不重复恢复余额 |
| `StoreEndAccess.check` | tenant/store/user/end/currentTime | 用可信端上下文；返回允许原因与最近时间边界 |
| `SubscriptionFulfillment.execute` | 原 taskNo | 不接受客户端传任意端 code 或到期日 |
| `SubscriptionConversion.apply` | orderNo、reservationNo | 仅原报价与支付事实，锁来源与余额，原子转换 |
| `EndSchedule.rebuild` | 门店、受影响 code、操作号、固定 Plan 周期、独立来源剩余量 | 持门店数字占用，保留已用片、FIFO 排未用片、核验时长守恒；与交付同事务 |
| `SubscriptionProjectionChanged` | storeNo、版本、下个时间边界 | outbox 事务事件，驱动缓存失效 |

后台任务：支付到期查单与关单、数字交付重试、异常退款查单、到期提醒、余额／转换／订单分摊对账、硬件库存预占释放、迁移差异校验。提醒任务不承担端权益到期判定的唯一责任。

## 8. 错误语义与客户端动作

符号名称为设计建议，不在本稿臆造正式数值错误码。

| 符号 | 触发条件 | 客户端动作 |
|---|---|---|
| `STORE_NOT_ACCESSIBLE` | 不属于该店／租户 | 返回可访问门店选择，不泄露订单与权益详情 |
| `STORE_BUSINESS_TYPE_MISMATCH` | SKU／端与门店类型不符 | 重新选商品，不能自动换店行业 |
| `END_ACCESS_NOT_PURCHASED` | 无该业务端来源 | 老板购买；店员联系老板 |
| `END_ACCESS_NOT_STARTED` | 仅存在未来周期 | 显示开始时间 |
| `END_ACCESS_EXPIRED` | 已购买但时间已结束 | 保留账号会话，显示续费 |
| `END_ACCESS_REVOKED` | 撤销／冻结导致不可用 | 显示原因与联系入口 |
| `END_CONTEXT_INVALID` | 端上下文签名、绑定或 audience 不符 | 重新建立端上下文，不凭 header 放行 |
| `QUOTE_EXPIRED` / `QUOTE_STALE` | 过期或订阅／余额版本已变 | 重新报价并让用户确认最新金额 |
| `SUBSCRIPTION_CHANGE_PENDING` | 该店已有数字购买／升级／退款占用 | 展示原单及处理中状态；纯硬件购买不受此限制 |
| `PLAN_UPGRADE_REQUIRED` | 试图普通购买覆盖另一档 | 转升级报价，不直接覆盖旧周期 |
| `PRODUCT_OFF_SALE` | 当前不允许新购 | 返回有效商品列表 |
| `PAYMENT_RESULT_UNKNOWN` | 尚未查明原支付结果 | 显示处理中，不重复支付另一单 |
| `FULFILLMENT_PROCESSING` | 已付款尚未完成交付 | 查询原订单，禁止提示再次购买 |
| `REFUND_DEPENDENT_CONVERSION` | 原权益已有升级后继 | 转人工核算链路 |
| `IDEMPOTENCY_CONFLICT` | 同幂等键不同参数 | 停止重试，刷新原单 |

## 9. 旧接口兼容

`GET /biz/plan`、`POST /biz/plan/trial`、`GET/POST /ops/merchant-plans...`、`GET/PUT /ops/plan-defs...` 当前对应主体合同。首期保留旧客户读取；已迁移门店的写入必须转到新合同或返回明确迁移提示，不能同时修改两套账。

官网可新增只读发布接口 `GET /public/business-types/{typeCode}/plans`，只输出已发布名称、端组合、期限、公开价格与停售状态，不泄露客户订单与内部折价规则；以它替代 `site/lib/plans.ts` 的固定种子展示。

运营端页面、动作权限、流程及为原型闭环需补清的列表／预览合同见 [运营端原型需求包](../requirements/平台运营端-经营类型Plan商城权益-原型需求包.md) §7.1。补充合同均为目标建议，不表示本API清单中的新接口已经实现。

## 10. 整体模块、认证与到期提醒补充

完整归属与认证方案见 [commerce整体设计](../superpowers/specs/2026-11-03-commerce-portable-design.md)，实施顺序见 [开发计划](../superpowers/plans/2026-11-03-commerce-portable-development-plan.md)。Plan、平台商城商品、订单、订阅／权益、抵扣和提醒统一由commerce提供门面；账号登录与原运营认证仍在宿主。

### 10.1 门店上下文及端准入合同

| 接口／场景 | 补充合同 |
|---|---|
| `GET /biz/auth/context` | 仅账号认证，列可访问门店和下一步；不先要求端证明 |
| `GET /biz/stores/{storeNo}/context` | 成员／治理核对后返回经营类型、模型、Plan、端状态、serverNow、nextBoundaryAt、entitlementVersion、临期摘要与可执行nextAction |
| `GET /biz/stores/{storeNo}/end-access` | 同code所有来源的连续期限为权威，返回currentCoverage、mostRecentEndedCoverage、nextStartAt；临期摘要不包含普通员工无权查看的价格／余额 |
| `POST /biz/stores/{storeNo}/end-sessions` | 账号fresh验证＋严格门店＋适用端／岗位／权益检查；签发绑定当前账号session指纹／门店／端／客户端／版本的短期证明 |
| 受保护端API | 仍带账号Bearer，并附端证明；路径、X-Store-No、实际解析门店和证明必须一致，不回落默认店；路由允许的整端集合不可由客户端改写 |
| 关键写 | 在实际本地事务中复核商业锁定状态／时间；宿主账号／成员以fresh复核通过时刻接纳，不能使用会话缓存代替 |

短期证明有效期不超过5分钟、账号到期时刻和下一真实商业区间边界。客户端自报clientId不是认证凭据；设备端另核宿主设备登记和凭据。证明错误／过期／旧版本先刷新正确店的端状态，不能改变账号归属或无限自动重试。系统回调／Job用服务端内部身份和可信事实合同，不要求买家在线或持有端证明；HTTP请求体不能自称SYSTEM来绕过认证。

运营端沿原operator认证链及commerce操作权限校验，不使用商家端证明。商家购买任何Plan均不能获得ops访问资格。

### 10.2 提醒查询与原记录重试

| 方法 | 路径 | 权限／行为 |
|---|---|---|
| GET | `/biz/stores/{storeNo}/expiry-notices` | 本店老板／采购权限及当前收件人范围；只读自己的站内到期记录。普通员工的可用性提示来自end-access，不开放采购通知列表 |
| GET | `/ops/commerce-expiry-notices` | 建议 `commerce:reminder:read`；按门店、端、阈值、覆盖终点、通道、状态分页 |
| GET | `/ops/commerce-expiry-notices/{noticeNo}` | 同读取权限；原收件人、策略、尝试、租约、回执及作废原因 |
| POST | `/ops/commerce-expiry-notices/{noticeNo}/retry` | 建议 `commerce:reminder:retry`、Idempotency-Key、原因；重试原目标原通道，发送前重新核验，不接受换收件人或任意群发 |

成功站内记录不因推送失败再次创建。SENT只表示站内持久成功或通道确认接纳，不等于用户已阅读；外部回执未知记UNKNOWN，查原消息或人工处理，不能盲重投。已经SENT／SUPERSEDED／窗口已过的原记录不因手工retry变成新的提醒。

提醒默认D7／D3／D1／D0按168／72／24小时及实际失效点计算；每15分钟按固定scanNow／upperAccountId分片分页，跨轮重扫依赖持久notice去重。不能只扫updated_at变化，否则会漏掉时间自然走到下一阈值的门店。D0读取最近结束区间，未提前建候选也能补发窗口内提醒；永久FREE端排除。

### 10.3 新增错误语义

| 符号 | 动作 |
|---|---|
| `END_CONTEXT_EXPIRED` | 账号仍有效时重新建立正确门店端上下文；不清账号token |
| `END_CONTEXT_STALE` | 权益版本变化，刷新门店准入后决定是否可重新签发 |
| `END_ACCESS_CHECK_UNAVAILABLE` | 付费新操作暂拒绝，保留账号及基础续费入口，受控重试 |
| `REMINDER_NOT_RETRYABLE` | 原提醒已成功／被替代／过期或状态不允许，只读查看原记录 |

正式数值错误码、共享类型、OpenAPI、运营权限与通知场景种子在实施对应任务同步，不在设计稿中臆造已发布编号。
