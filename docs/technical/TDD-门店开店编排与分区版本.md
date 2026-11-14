# 门店开店编排与分区版本
状态：本地实现与验证完成，尚未部署到 166。日期：2026-11-14。档位1。依据：既有商户门店需求与统一经营体验计划 T03–T04。
## §0 需求到设计
| AC | 需求 | 设计 |
|---|---|---|
| AC1 | 请求重试不重复建店，未知结果可查询 | 账号范围 requestNo、意图摘要、同事务成功回执 |
| AC2 | 复用实体或新建实体，免费默认，不继承渠道 | 复用 quickStart/branch 与原权限额度事务 |
| AC3 | 基础和营业时间保存冲突不覆盖 | 分区内容版本、同一所有权锁内比较并更新 |
| AC4 | 旧包兼容与权限不提升 | 新接口/能力字段，保留旧接口；新写操作使用既有权限 |
## §1 模型与边界
新增 mch_store_opening_request，成功回执与建店同一事务。失败整体回滚；NOT_FOUND 表示尚无已提交回执，可用相同编号和意图重试，不代表可以更换编号。禁止永久 IN_PROGRESS 孤儿记录。来源账号键包含 tenant/subjectKind/userNo，不能跨账号查询。无 outbox。
设置版本是分区内容 SHA-256，不作为权限或全店递增序号；在原 ownership 锁内读取并比较，兼容已有写入口；不同分区互不冲突。同值 ABA 不损失业务字段，允许。基础保存一次事务，取消不发请求。无版本能力的旧服务保持旧接口，不能宣称已具备冲突保护。
## §2 模块与实施顺序
1. 开店 service/impl 与唯一回执表，接口仅做校验和身份转换。
2. 设置 service/impl 使用原锁、原 store 行、局部字段更新；读出 allowedActions 与分区版本。
3. Flutter API/Mock/页面接入，同一请求编号重试与查询恢复；组件组合四业态首页。
## §5 验证
已执行：本机 MySQL9 回执事务／回放／账号隔离／并发与回滚、分区版本并发、真实账号会话 POST＋GET 恢复、原建店控制器与 starter 路由，共 37 项通过。架构 23 项中 1 项既有条件跳过；端点权限 12 项、地区策略 7 项通过。没有使用 H2。

Flutter 定向回归和静态检查通过；模拟器完成四业态首页与设置冲突、首店四步创建五个场景，保留 17 张截图。Mock 验收不代表 166 已发布。源证据位于 App 的 docs/review/store-experience-v38-2026-11-14/README.md。

## §3 API 与准入
- POST /biz/merchant/store-openings：账号级准入，requestNo 为 1–64 位字母、数字、下划线或连字符；可选 entityNo/sourceStoreNo，fields 沿用 quick-start 校验。新实体仍要求个人商家身份；复用要求拥有源店上下文与额度。
- GET /biz/merchant/store-openings/{requestNo}：按 tenant/subjectKind/userNo 隔离，仅返回本账号已提交的成功回执。
- GET /biz/store/settings：选店准入，返回 profile/sectionVersions/allowedActions；POST /basic 用 biz:store:admin，POST /hours 用 biz:store。未知方法或子路由拒绝，settings 不解析成门店号。
- storeOpeningSupported 能力缺失时 App 走旧协议；旧协议不承诺同号回执恢复。标准 API Markdown/OpenAPI 与 App 镜像已同步。

## §4 Review 与发布
回执与创建同事务，没有异步 saga 或独立草稿任务。保留原实体数量上限及免费初始化，待补登记实体先恢复，已有效实体允许在原额度内再新建。复用实体的新店仍需独立支付开通。设置内容摘要不是权限凭据，也不是全店单调版本；旧写入口采用同一所有权锁，内容变化仍会让新版写入冲突。

服务分层以 merchant service/impl 为核心，portal 仅校验转换；地区策略由独立 Port 适配器委托 Service，避免 Service 直接继承 SPI。V667 只追加回执表，部署前须核对目标迁移版本并按既有流程执行。当前未修改 166 或 NearPay。
