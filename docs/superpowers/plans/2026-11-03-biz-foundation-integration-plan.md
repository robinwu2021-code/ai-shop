# B 端注册、开店、进件与基础 Plan 对接实施计划

用户已确认：先完成三档展示、FREE 初始化和当前权益查询。原 T06 报价／订单工作尚未写产品代码，暂停其扩展，优先交付此基础链路。依据已批准的 commerce 总体设计、T03–T05 实现和现有 B 端接口，不重新设计认证、商户资金或订阅模型。

**目标**：真实账号注册／登录 → 经营类型 → 创建首店并初始化 FREE → 恢复／选店 → 提交证照及按需支付进件 → 查看三档与当前商业权益；交付可供 B 端会话直接实施的 API 文档。

**边界**：后端和文档。B 端本轮不改页面、不下付费 Plan 订单、不启用真实生产配置。支付商户进件复用现有通道合同，不等于付费 Plan 收款；真实短信／支付渠道未配置时不得伪造线上成功。

**Spec**：`docs/superpowers/specs/2026-11-03-commerce-portable-design.md` §3–5、`2026-11-03-industry-plan-mall-design.md` §3–5，以及用户本轮范围。现有接口和实际 DTO 是对接真源，原 API 规划清单仅为候选路径。

## Global Constraints

- commerce-api 只依赖 JDK，core 只依赖 api/JDK；宿主仅调用公开 API 与 starter，不在新桥接代码导入 core/persistence。
- 不改变长期账号 token 的门店／Plan 无状态性；USR/MCH 类型来自可信会话，登录时尚无 SecurityContext 也不得按裸编号猜类型。
- 沿用 `{code,msg,data}`；401 仅代表账号无效，商业拒绝不清 token。明确请求店、路径店、进件目标主体／店，不回默认店替代执行。
- FREE 必须加入建店同一事务，重试不重发、不用最新目录覆盖既有 FREE。旧主体额度 Plan 与新门店商业 Plan 分开，LEGACY 不自动迁移。
- 只展示当前发布且可对 B 端展示的目录，不能泄露运营 CatalogView 的草稿、历史、锁定 token 或非公开信息。三档按 FREE／PRO／CHAIN 返回，不造线上价格或验收数据。
- 当前权益来自实际来源和时间片，按 `[start,end)` 与服务端时钟计算，保留真实空档。员工不接收实付／折价基数；只读商业状态不代替岗位及端证明。
- 未登记新模型路由默认拒绝；新登记的账号／基础进件接口不能成为请求体或查询参数跨店后门。保留编码路径 400、类型化主体恢复和混合主体保护。
- 现有 commerce V1–V5／宿主 V554 不改写；确有新 DDL 再加版本。默认关闭及有界连接池上线前门槛不被本轮抹掉。

## Review Focus

1. 实际初次登录返回的 profile 也必须类型化，不能仅修复认证后的 GET；老板／员工回退过程中不能留下多余 btk_／ctk_ 会话。
2. 新门禁下必须能完成证照和收款进件；被拒、审核中、通道不可用、聚合／直收状态不可混成成功或付费过期。
3. 支付进件的 body/query entityNo/storeNo 与请求店必须核对，允许同一人拥有另一主体不等于允许用当前店证明操作它。
4. 类目／经营类型空目录和未发布配置必须显示不可用，不能返回虚构三档或把运营草稿公开。
5. 当前 FREE 显示首次绑定版本；经营类型停用或新版本发布不应改写已购权益，员工无采购权时仍能查商业状态但不见价值明细。

### Task 1: 认证、建店和进件基础链路

**Files**：修改 `backend/shop-app/.../portal/biz/BizAuthController.java`、`BizMerchantController.java`，必要的 merchant 公开服务／SPI、`commercebridge` 基础入口验证和 starter `security/CommerceRoutePolicy.java`；测试 `backend/shop-app/src/test/java/ai/neargo/shop/commercebridge/BizFoundationJourneyFlowTest.java`，复用现有注册、申请、进件和 T04/T05 测试。

**Interfaces**：保留现有 OTP 登录即注册、登录 response 的 token/merchant；登录资料组装增加可信 LoginUser 入参，调用 `resolveAuthenticated`，员工和个人读自己的信息。实现真正 POST `/biz/auth/logout`，撤销本次 MERCHANT 会话。公开端点仍沿现有命名，新增响应字段须为兼容的可选增量。审核、进件服务和平台资金真源不迁入 commerce。

- [ ] 写失败测试：首个手机号登录真实建立个人账号并只返回 btk_；老板／纯员工／新账号分支与冷启动一致，USR/MCH 同号不串资料；注销后旧会话和端证明不能继续受保护操作。
- [ ] 写失败测试：先选已发布类型再 quick-start，同事务 FREE、重复返回原店、异类型拒绝；普通分店继续验证 operationNo；店员不能冒充申请人或擅自进件。
- [ ] 写失败测试：启用 commerce 后真实申请／协议／资质及支付进件入口可按独立岗位运行，无商业证明也能完成基础步骤；body/query/path/headers 的主体与门店矛盾在任何通道调用或写入前拒绝。
- [ ] 实现最小精确路由注册及信任边界。只核对用户确认的基础链路和其必需字典／上传依赖，不整体开放业务命名空间。真实外部通道仍采用原幂等／回查流程，不在新事务中假装通道成功。
- [ ] 定向 RED/GREEN 后跑相关现有 `MerchantPaymentFlowTest`、`MerchantApplyEditFlowTest`、`OnBehalfMerchantFlowTest`、`QuickStartFlowTest`、认证和端点清单测试；用原生 MySQL 验证新增集成边界。记录真实服务、mock 外部依赖和未验证渠道。
- [ ] 提交后由独立 task reviewer 审核。

### Task 2: 三档展示、当前 FREE／权益与 API 交付

**Files**：新增 `commerce-api/.../api/foundation/FoundationQueryFacade.java` 及只读 DTO；core `foundation/FoundationQueryService.java` 与内部读取仓储；persistence 的对应 JDBC 实现；starter 的装配；宿主 `portal/biz/BizFoundationController.java` 与只读身份适配。新增 core／原生／宿主读合同测试。创建 `docs/api/B端基础链路-对接文档.md` 和可复用的请求／响应例子；更新旧 API 说明的实现状态入口。

**Interfaces**：公开 facade 提供 businessTypes(actor)、plans(actor,typeCode)、store(actor,StoreRef)、subscription(actor,StoreRef)、entitlements(actor,StoreRef)。HTTP GET `/biz/business-types`、`/biz/business-types/{typeCode}/plans`、`/biz/stores/{storeNo}/context`、`/subscription`、`/entitlements`。保留 T05 `/end-access` 的单端查询合同，不原地改成汇总。DTO 明确类型、模型、发布版本、可展示价格选项、实际 FREE、当前／未来区间、serverNow、nextBoundary 和下一动作。新 HTTP 时间使用毫秒整数或明确文档化现有接口的真实表示，不静默全局改变序列化。

- [ ] 失败测试：只读账户能看公开已发布三档，草稿／历史／禁售 SKU、无关类型及租户不可见；未配置不得伪造。所有付费购买入口明确 purchaseAvailable=false，不生成订单。
- [ ] 失败测试：同一店 FREE 当前版本等于首次绑定版本；原 source／多端累计覆盖与真实空档正确，员工读结果不含金额基数，超时商业资格不变为账号 401；LEGACY 返回明确旧模型边界，不虚构新 FREE。
- [ ] 实现查询 facade 和最小 HTTP，所有宿主身份／成员／治理来自可信适配，不提供写来源或更改类型能力。当前权益读取加入相同 DataSource 的一致性策略，不查半份排期。
- [ ] 通过实际 HTTP 验证：账号无店→类型／三档→建店→FREE→提交／查看进件→冷启动恢复→当前权益；端点清单、角色、编码路径、进件跨目标和类型碰撞回归继续通过。
- [ ] 按实际控制器／JSON 整理可对接 API：方法、headers、请求／响应、字段类型、错误码、状态流转、调用顺序、重试／幂等、旧新模型区分、运行配置／测试边界。明确已实现与预留，提供 B 端改动清单，付费购买不在本轮。
- [ ] 核对文档示例与实际 DTO／运行响应，必要的共享 API 类型同步不代替修改 UI。定向测试、独立 commerce verify、宿主聚合与相关兼容性检查通过后整体 review。

## 收尾和交接

- 在当前隔离分支上提交代码、测试和实际对接文档，保留原先未跟踪设计输入，不推送／部署或改真实数据。
- 检查宿主既有三项 print 架构失败的完整信息与基线相同；不声称全宿主测试通过。
- 若能唯一定位现有 B 端会话，完成后把接口文档、提交与限制发给该会话；不新建用户会话。不在开发完成前派发未确定合同。
