# TDD-业务编码统一生成

状态：批 1-3 实现完成，全量后端 2597/0/0（待提交）
档位：2（新生成算法 = 架构级准不可逆；跨系统；改所有新码格式 = 对外契约）
关联：[ADR-033 业务编码统一生成](ADR/ADR-033-业务编码统一生成.md)
创建：2026-10-09

## §0 对账一 · 需求 → 设计

用户：「优化订单编号逻辑，梳理其他业务编码，整合到一个工具统一处理。」
拍板（2026-10-09）：订单号目标＝多实例不碰撞 + 不可枚举 + 更短 + 带日期；整合＝换统一算法；随机段 10 位。

| AC | 需求 | 落点 |
|---|---|---|
| AC1 | 一个工具统一生成，格式 `<前缀>yyMMdd<10随机>` | `BizKey.next` 重写 |
| AC2 | 多实例不碰撞 | 无共享计数器（纯随机 + 日期） |
| AC3 | 不可枚举 | 随机段替掉递增 seq |
| AC4 | 更短 | 前缀+21 → 前缀+16 |
| AC5 | 运营可读日期 | yyMMdd 段 |
| AC6 | 前缀全唯一（修 5 对撞车） | 常量改值 + 守卫 |
| AC7 | 旁路收回（员工号不再自己拼） | 改 `OpsServiceImpl:1000` 为 `BizKey.next(STAFF)` |
| AC8 | 禁止将来再 inline 生成 | 守卫 `BizKeyConventionTest`（字面量前缀 `setXxxNo` + 同语句易变源） |
| AC9 | 反解前缀的 parser 不被新格式破坏 | `MpStoreController` 的 `startsWith` 在其输入域仍正确，**无需改** |

**孤立项**：无。

> **复核修正（2026-10-09，批 2 落地时）**：原计划写「2 处旁路」「改 `MpStoreController` 精确化」，核查后收窄：
> - **规格码不是旁路**。`SpecLibraryServiceImpl` 里 `"M"+hash` 是 `setCode`（规格**维度/值的内部局部码**，`M`=商家域、短码、维度内引用），
>   真正的业务键 `valueNo`/`dimNo` 早已走 `BizKey.next(SPEC_TEMPLATE)`。把它收进 BizKey 会把两个概念混为一谈，故不动。
> - **`MpStoreController` 的 `startsWith("M")`/`startsWith("ST")` 不是雷**。该控制器的 `no` 入参只会是商家号或门店号，
>   两者前缀互斥（`M` vs `ST`），判定在此输入域里确定且正确；`startsWith` 本身是读侧耦合、与本次格式改动无关，不改。

## §1 现状与根因

- `BizKey.next(prefix)` = `前缀 + yyyyMMddHHmmss(14) + seq(4,每JVM mod 10000) + rand(3)`，123 处调用。
- 四毛病（见 ADR）：多实例碰撞 / 可枚举 / 长 / 到秒时间戳无用。
- **5 对前缀撞车**，且 `startsWith("ST")` 反解 → 功能雷。
- **1 处旁路**：员工号 `"E"+System.currentTimeMillis()%1e8`（`OpsServiceImpl:1000`）。全仓扫
  `setXxxNo("字面量"+易变源)` 仅此一处（规格码那三处是 `setCode` 的维度内部码，不在列，见 §0 复核修正）。
- 订单号/子单号/支付/结算有唯一索引（随机算法的 DB 兜底成立）。
- **隐性耦合两处**（旧码「前缀+时间戳+递增seq」的字符串序恰好=创建序，被默默当排序用）：
  `OrderServiceImpl` 落店候选、`BizIdentityResolverImpl` 默认店解析。随机段一上字符串序变任意序 → 落到别家、不报错。

## §2 方案（= 实施计划，分三批）

### 契约变更

- **对外 JSON / 库表值**：所有**新生成**的业务码格式变（旧码不动，共存）。
- 端点 / 权限码 / i18n / 配置项：无。

### 前缀撞车修复（留载前缀的、改另一个）

| 前缀 | 留（被用/被反解多的） | 改 |
|---|---|---|
| ST | STORE（3 处 + startsWith 反解） | STAFF（0 处）→ **STF** |
| CM | CAMPAIGN | CHANNEL_MESSAGE → **CHM** |
| MT | MEMBER_TAG | MATERIAL → **MAT** |
| PP | PICKUP_POINT（3 处） | POINTS_POOL → **PPL** |
| SL | CONTENT_SLOT | STAFF_LOG → **SFL** |

新值 STF/CHM/MAT/PPL/SFL 已核不与任何现有前缀相等。存量：被改名的类型旧码保留旧前缀，无按前缀反解它们的代码。

### 分批

| 批 | 内容 | 判据 |
|---|---|---|
| **1** | `BizKey.next` 重写（日期+Crockford随机）+ 修 5 对前缀 + `BizKeyTest`；附带修两处隐性排序耦合（见 §1） | AC1-AC6 |
| **2** | 收 1 处旁路进 `BizKey.next`（员工号 → STF）；`MpStoreController` 复核确认无需改（AC9 转为「验证不受影响」） | AC7 AC9 |
| **3** | 加 `BizKeyConventionTest`（禁止再 inline 生成业务码） | AC8 |

### 明确不做

- 不改存量数据、不加分隔符、不定长前缀（见 ADR）。
- 不动 123 个调用点（它们调 `BizKey.next(BizKey.XXX)`，重写内部即自动生效）。

## §5 对账三 · 实现 → 需求（测试）

| AC | 测试 | 跑过 | 消融 |
|---|---|---|---|
| AC1/AC4/AC5 | `BizKeyTest#格式=前缀+yyMMdd+10随机` | ✅ 6/6 | 长度改回 → 红 |
| AC2/AC3 | `BizKeyTest#随机段真随机_不含递增与秒时间戳` | ✅ | 换回 seq → 红 |
| AC3 | `BizKeyTest#Crockford字母表不含ILOU` | ✅ | 放进 I → 红 |
| 碰撞 | `BizKeyTest#十万次无重复` / `#并发5万无重复` | ✅ | 随机段缩到 2 位 → 红 |
| AC6 | `BizKeyTest#任意两个前缀常量不相等`（反射扫 >60 个常量） | ✅ | 留一对撞车 → 红 |
| AC8 | `BizKeyConventionTest#noInlineBusinessKeyGeneration` + `#theDetectorActuallyFires`（自检） | ✅ | 合成坏样本不报 → 自检先红 |

## §6 对账二 · 设计 → 实现

- **批 1**：`BizKey.next` 重写（`FMT=yyMMdd`、`CROCKFORD`、`RAND_LEN=10`、`SecureRandom`，去 `AtomicInteger`）；
  5 对前缀改值（STF/CHM/MAT/PPL/SFL）；`BizKeyTest` 6 条。
  附带两处隐性排序耦合：`OrderServiceImpl`（落店候选去掉 `.sorted()`，用 `own` 自带的 id 升序=创建序）、
  `BizIdentityResolverImpl`（默认店改一条 `is_default DESC, id ASC` 有序查，过滤到授权候选取首个）。
  **全量后端 2597 测试**：改格式先暴露 15F+1E（全 Store 系），修两处耦合 → 2F，再治翻页脆弱 → **0F**。
  - 翻页脆弱（非生产 bug）：`StoreScopedVisibilityFlowTest` / `StoreStockFlowTest` 里查 CM001 目录的用例单独跑绿、全量红，
    且**失败在用例间漂移**（每轮随机 ID 不同，红的那两条就不同）——典型共享库顺序依赖。根因：`/mp/goods` 服务端 `size` 封顶 50，
    CM001 是共享种子社区、全量跑累积大量货；旧单调 ID 下目标货排在前 50，随机段后落到任意一页，「只看第一页」成了随机假红/假绿。
    修法：两类各自的共享 helper `buyerSees` / `catalogRow` 改成**翻页遍历所有页**（按货号精确命中），
    一次性覆盖两类全部调用方、不依赖标题，语义不变。（先试「加唯一 keyword 收窄」只修了当轮那两条，下轮漂到别的用例——
    证明必须治根因、按页遍历。）另 `savingSameSubsetTwiceDoesNotCollide` 与另一用例共用手机号的跨测试污染 → 给独立号 `12600180019`。
- **批 2**：`OpsServiceImpl:1000` 员工号改 `BizKey.next(BizKey.STAFF)`（staffNo 无前缀反解、列 VARCHAR(64) 够长）。
- **批 3**：`BizKeyConventionTest`（arch 包，扫 backend 全模块 main 源码，带自检对照量）。

## §7 确认与完成

| 日期 | 事件 |
|---|---|
| 2026-10-09 | 用户拍板方向与随机段长度；档位 2；ADR + 本 TDD；开始批 1 |
| 2026-10-09 | 批 1-3 实现；全量后端 2597/0/0；复核收窄 AC7/AC9（规格码非旁路、`MpStoreController` 不改） |


## §8 统一业务单据编号规范（2026-10-20 设计，尚未切换生成代码）

### §8.1 定位与现状

人工查单使用短业务单号，内部主键、幂等标识、事件标识和安全凭证各自保留职责。新业务单号采用“前缀 + yyyyMMdd + 至少6位十进制序列”，两位前缀通常16字符、三位前缀通常17字符。查询、复制和对账使用完整单号；日常列表按产品需要展示，到账结果不附带内部标识。

本节是后续实施规范，不追溯修改ADR-033、ADR-035的历史实施事实，也不表示线上生成器已经替换。

| 当前类型 | 实现及格式 | 本次处理范围 |
|---|---|---|
| 普通业务实体编码 | BizKey.next：前缀 + yyMMdd + 10位Crockford随机串 | 商品、门店、会员等实体编码继续保留；不批量换键 |
| 订单主单/子单 | OrderNoGenerator：O + yyMMddHH + 4位循环序列，子单追加序号 | 现行ADR-035保留，单独安排升级；新规范不允许回绕 |
| 储值/支付权益内部标识 | StoredValueFacts.no及EntitlementRecords：前缀 + 长摘要 | 保持幂等、外键、事件、原单恢复的稳定性；另持久化短业务单号 |

储值计划充值目前使用PR加60位十六进制摘要，共62字符。它由租户、主体、操作员、请求键推导，不是仅用于展示的随机字符串。直接截断或替换公共no函数会改变重试原单定位以及凭证、批次、事件的关联方式，不能作为缩短业务单号的实现方式。

### §8.2 标识分层

| 标识 | 例子/字段 | 生成与用途 |
|---|---|---|
| 数据库主键 | id | 数据库生成，只作表内关联 |
| 内部业务标识 | rechargeNo、operationNo、eventNo、accountNo | 现有规则不变；接口定位、原单恢复、事件关联使用 |
| 人工业务单号 | documentNo，例如PR20261020000001 | 所属业务服务生成并持久化；查单、小票、对账使用 |
| 请求幂等键与事实摘要 | Idempotency-Key、payloadHash | 原请求键及完整事实摘要；与documentNo分离 |
| 安全凭证 | 会员码、核销令牌、票据 | 沿用随机/签名及有效期校验，不改成可枚举序号 |

新单据必须有documentNo，接口保留原定位字段。历史单据没有documentNo时允许为空，页面隐藏该字段，不把内部长标识冒充短单号。未映射的旧号仍可通过原接口查询。

### §8.3 格式与前缀登记

格式：`<登记前缀><yyyyMMdd><序列>`，序列从000001开始，宽度至少6位。

| 单据类型 | 归属 | 前缀 | 示例 | 说明 |
|---|---|---|---|---|
| 储值充值单 | Pay | PR | PR20261020000001 | 沿用当前充值语义；内部rechargeNo另存 |
| 权益核销记录 | Pay | EU | EU20261020000001 | 一条核销记录一个号；组合支付各记录关联原operationNo |
| 储值账务凭证 | Pay | SV | SV20261020000001 | 一张凭证一个号，主要用于运营对账 |
| 营销活动 | Core | PT | PT20261020000001 | 后续接入，活动内部标识不迁移 |
| 卡券模板 | Core/营销 | PC | PC20261020000001 | 后续接入，不等同于用户券核销码 |
| 发券批次 | Core/营销 | PI | PI20261020000001 | 后续接入，同批次重试不重新编号 |
| 订单、子订单 | Core/订单 | 现行O及后缀 | O261020180001、O261020180001-1 | 本阶段继续现行规则，升级需单独兼容ADR-035 |

前缀表只约束新增documentNo命名空间，不能用于反解旧内部ID的业务类型。登记项必须同时记录documentType、前缀、唯一所属服务、默认展示宽度和版本；同一前缀只能由一个服务拥有。现有BizKey的SL等前缀与Pay内部派生标识存在交叉，不能直接把所有历史前缀混进一个推断器。新类型通过登记表扩展，调用方不得传任意字符串前缀。

规则：

- 日期为4位年份、2位月、2位日，避免两位年份循环歧义；不编码门店、手机号或会员身份。
- 日期由配置明确的业务时区计算并固定；当前默认Asia/Shanghai，禁止使用JVM默认时区隐式决定。变更业务时区需要评审及切换边界方案。
- 每种单据、每个业务日期维护一条全租户共享序列，序列命名空间不按门店或商家重置，因此无须在单号中塞主体编码。
- 同日超过999999时扩展为1000000，编号增长一位；绝不取模、截断或回绕。VARCHAR(32)留扩展空间，格式校验允许序列超过6位。
- 不承诺连续无空号。失败、取消、重启丢弃的号码不重用；号码也不作为排序或统计单量的可靠依据。
- 单号可枚举，所有查单仍校验租户、主体、门店和操作权限；不能凭单号获取资金或核销权益。

### §8.4 号段、并发与幂等流程

采用“所属服务数据库原子领段 + 进程内发号”。Core复用sys_sequence能力，Pay使用Pay归属的序列表和payTxManager，禁止Pay在资金事务中远程向Core领号。

1. 在拥有服务入口检查原幂等命令或原业务记录。已存在的请求返回已持久化documentNo，不重新编号。
2. 无原记录时，按documentType和可信业务日期取号。号段领取独立短事务提交，初始段长100、可配置；订单现行段长10不在本阶段改变。
3. 在进入资金写事务前，为本次操作预留全部具体号码；号段不足时在此前补充。资金写事务内只绑定本次已预留号码，不再从共享缓存临时领取或补号。禁止持有资金连接后再开启REQUIRES_NEW等待第二条连接，避免连接池耗尽。
4. 在所属业务事务中持久化单据、documentNo映射及幂等结果，成功后返回。重复请求并发抢占同一业务时只允许一个映射胜出，其余复用胜出的结果；预取号码可作废。
5. 幂等参数摘要仅包含原始业务事实，不把预取序列、当前日期等临时生成值加入请求指纹。同键异参继续拒绝，跨日重试保持首次成功的单号。
6. 领段游标在独立事务中永久前进，业务失败不回退；实例重启不接着使用遗失号段。多个实例只能领取不相交的范围。
7. 数据库唯一约束为最终防线；唯一冲突先区分同源映射、号码异常和无关约束，再作有限重试或告警，不能把业务重复转为新充值。

前置领号只是性能/容量步骤，不代表授权或收款成功；余额变更、账务凭证、Outbox及幂等结果仍保持既有事务边界。异步重放读取业务原记录的documentNo，不在事件消费线程里重新生成。

订单现有4位展示取模在同一小时超过9999个号码时必然耗尽可用空间；查询重试无法扩充空间。后续订单升级必须使用不回绕序列或新的版本命名空间，不能把现行取模方法复制成统一生成器。

### §8.5 数据库与API落点

为兼容内部标识及既有资金摘要，首批采用所属服务内的独立号码映射；不修改已冻结收款、凭证和事件的内部ID。

| 表/能力（待实施） | 关键字段与约束 | 归属 |
|---|---|---|
| pay_document_sequence | seq_key（类型+业务日期）主键、next_value BIGINT、updated_at；独立原子领段 | Pay |
| sys_sequence | 现有能力，新增类型/日期命名空间需遵守seq_name长度约束 | Core |
| pay_document_number | id、tenant_no、document_type、source_no、document_no、business_date、created_at；UNIQUE(document_no)，UNIQUE(tenant_no,document_type,source_no) | Pay |
| core_document_number | 同上，与Core业务记录同事务提交 | Core（后续批次） |

source_no为内部原单/原核销记录/原凭证号，保持原值。document_no VARCHAR(32)，source_no维持现有64位兼容长度；业务日期单独存DATE，编号版本登记在类型配置中。财务主键、签名摘要、原事件载荷不因新增映射变化。

接口新增可空documentNo，原rechargeNo、businessNo、operationNo等现有字段语义不变。新业务服务创建的单据在事务成功后必须返回非空documentNo，历史记录暂允许空。充值结果、账户收支列表、核销详情及运营查询按需携带，列表批量查询映射，避免每行额外一次数据库访问。

人工查单入口支持完整documentNo并解析至原内部标识，随后走已有权限和业务查询；旧内部号查询兼容保留。API路径先继续使用内部原单号，前端不得把documentNo直接替换到旧路径。所有客户端按字符串保存单号，禁止转数字、按固定长度截取日期或用单号排序。

### §8.6 模块与类职责（待实施）

| 模块 | 职责与拟新增类型 |
|---|---|
| shop-base | DocumentType/DocumentNoFormat：类型登记、前缀、格式校验；不访问数据库，不领取号段 |
| shop-store-mybatis | 可复用的号段值对象/本地分配器支持；不替业务域决定编号事务 |
| pay-domain | PayDocumentSequenceMapper、PayDocumentSegmentSource、PayDocumentNumberService、PayDocumentNumberMapper；Pay本地序列和映射 |
| shop-core | CoreDocumentNumberService及Core号码映射，活动、发券批次接入 |
| shop-app / pay-svc | 入口编排及DTO适配；完成授权、原命令查询和前置号段准备，资金事务中落映射 |
| b-app / ops-web | 使用documentNo展示/复制和检索；成功提示不自动展示内部号 |

### §8.7 实施批次与验收

| 批次 | 工作与验收 |
|---|---|
| 1：储值单号 | 建立Pay序列、类型登记和号码映射；新充值单及对应运营查询返回短号；历史恢复和原事件重放不受影响 |
| 2：核销/凭证 | 覆盖权益核销记录与储值凭证，明确组合核销一操作多明细的对应关系 |
| 3：营销单据 | 活动、券模板、发券批次接入Core本地号码能力，保持活动Core、权益Pay的现有边界 |
| 4：订单与历史映射 | 单独升级订单取模规则；按需、可续跑地补历史documentNo映射，不重写任何原始资金标识 |

定向验收覆盖：格式和前缀登记、不同实例号段不重叠、同日超六位扩宽、跨日及明确时区、重启/事务回滚不重用、同键重试/同键异参、并发重复业务只留一个映射、原事件重放不再编号、历史号可查询，以及Pay小连接池下领号不引入嵌套持有连接。每批先基本编译及相关单测，已通过的旧链路不重复全量回归。

### §8.8 边界与取舍

- 统一的是人工业务单号规范与分配协议，各业务服务分别拥有序列和事务，不新增跨服务中央取号依赖。
- 可读序列会暴露大致发号量，号段空洞和失败号码也会计入；如有防业务量推断要求，另用受控随机查单凭证，不能把短序号当安全令牌。
- 首批仅落储值人工业务单号，其他批次按上述矩阵实施。旧内部号永久保留，无在线批量换号计划。
- 日期取原始可信业务时间；历史映射只在需要展示时分批补齐，并持久化后保持不变，不在每次读取时即时生成。


## §9 全系统同类需求盘点（2026-10-20 静态审查）

### §9.1 结论与范围

储值长单号不是孤立需求。当前系统同时存在内部摘要号直接展示、UUID凭证号用于人工查单、同类记录采用不同编号规则、单号语义混用，以及生成规范与架构守卫覆盖不一致的问题。统一按§8新增人工业务单号，不能把所有名为`*No`的字段一律替换。

本次检查覆盖仓库内Core、Pay、库存桥接代码，以及B端、C端、运营端的编号生成、展示、查询和格式解析。以下为源码确认结果；未执行运行时全链路验证，未修改生成代码、数据库或历史数据。P1表示近期实施或兼容性前置事项，P2表示后续统一；容量风险不等同于已经发生线上故障。

### §9.2 功能需求矩阵

| 编号 | 归属及对象 | 已确认现状 | 整理后的需求 | 优先级 |
|---|---|---|---|---|
| NUM-01 | Pay／B端储值收支详情 | `businessNo`来自凭证`bizNo`，详情直接展示；计划充值内部号PR+60位摘要，共62字符 | 充值单映射短号；核销业务号解析到对应核销记录短号。DTO新增documentNo，保留businessNo定位语义，历史无映射时不显示长号 | P1，首批 |
| NUM-02 | B端历史储值恢复页 | 存在旧本地缓存时仍进入LegacyRecharge，结果卡直接显示rechargeNo | 与新储值结果保持一致，到账状态旁不展示内部号；历史恢复、取消、查询继续使用原号 | P1，首批 |
| NUM-03 | Pay权益核销 | 普通核销EU+32位UUID，共34字符；储值核销EU+60位摘要，共62字符 | 两条核销路径统一人工单号EU；保留各自usageNo，组合操作与明细分别关联，不合并不同核销事实 | P1 |
| NUM-04 | Pay权益发放、卡券实例 | 实例EI+32位UUID，分录EL+32位UUID；发放事件另有摘要号 | 发放单／批次按人工查单需求编号；券实例号继续用于资产定位，安全核销凭证单独管理。不得为缩短展示而修改已发资产主标识 | P1，与权益批次一起 |
| NUM-05 | Pay积分／B端积分记录、对账单／运营积分查询 | 凭证号为36字符UUID，分录及资金流水也用UUID；B端和运营表格直接展示，B端对账导出含voucherNo | 为积分凭证新增短号；默认展示凭证短号，运营详情保留可复制的原凭证／分录／流水号；导出区分“业务单号”和“内部凭证号” | P1 |
| NUM-06 | Pay旧券发放与迁移 | 旧券发放LI、批次LB使用前缀+62位摘要，共64字符；迁移阶段MS+60位摘要 | 发放批次、迁移计划／导入／阶段按人工操作对象增加短号；冻结快照、源券映射、发放幂等和重跑游标保留原号 | P1，运维查单批次 |
| NUM-07 | Pay权益对账与异常处置 | 对账运行RR、差异RD、异常EX、处置EA均存在前缀+60位摘要 | 优先给对账运行、异常工单增加人工单号；差异、处置动作默认放详情，保留原事件链。本项已确认服务端需求，不能据此认定运营页面已经接入 | P1，运维查单批次 |
| NUM-08 | Core库存桥接／B端库存同步记录 | AUTO-/SYNC-+60位摘要为65字符，ALIGN-+60位摘要为66字符；同步记录直接展示operationNo | 同步任务／调整批次使用人工单号，摘要operationNo继续幂等；逐行操作号放详情，不默认作为记录标题 | P1 |
| NUM-09 | Core库存桥接／B端库存对齐 | batchNo为AL+32位UUID，共34字符，页面直接展示 | 为对齐批次增加可查、可复制短号，保留batchNo及确认接口定位；不改变外部库存服务的单据所有权 | P2，可与NUM-08合并 |
| NUM-10 | Core活动、券模板、发券批次 | 新营销使用PT／PC／PI等BizKey编码；旧营销券模板与用户券共用COUPON前缀，旧发放记录额外追加-I | 按§8接入Core人工单号；界面明确区分活动、模板、用户券、批次。老、新营销兼容期间按对象类型解析，不能只看前缀；现有编码不是62位长号故障 | P2 |
| NUM-11 | Core订单生成 | O+小时+四位循环序列；同小时最多9999个可用展示号，存在容量上限；日期依赖JVM默认时区 | 单独升级ADR-035，取消回绕、明确时区；升级前检验所有格式消费者及渠道长度限制，不在储值首批顺带换订单号 | P1设计前置，单独实施 |
| NUM-12 | C端订单展示及支付跳转 | 列表以“订单号”展示payOrderNo或orderNo；orderNoOf通过去掉末尾-数字还原支付重试号，其注释假定订单不含短横，但当前子单存在-序号 | 明确主订单／子订单／支付尝试号的字段与文案；核对微信返回的是哪一种号，优先服务端显式关联。增加“子单-1”和“支付重试-2”兼容用例；源码存在规则假设冲突，尚未证明实际误跳转 | P1兼容性核查 |
| NUM-13 | Core售后、开票；Pay提现、结算 | 已使用BizKey；部分结算开票号由结算前缀另加I后缀 | 纳入人工单号类型登记，按用户查单价值分批接入；与支付渠道原始流水号、税务发票号码分字段，禁止替换外部法定／渠道编号 | P2 |
| NUM-14 | 基础规范与架构守卫 | BizKey类注释仍描述时间戳+序号，实际为yyMMdd+随机串；现有守卫只抓特定setNo拼接，无法覆盖局部变量、构造器或摘要助手 | 修正文档和实际实现差异；按“实体码／人工单号／内部幂等／安全凭证”建立允许规则，补登记及绕过规则的定向检测，不能把合法内部摘要一概禁止 | P1，首批规范配套 |

补充核查项：C端卡包直接展示cardNo，但本轮未完成该字段到真实资产来源的逐层映射，暂不按长号缺陷安排迁移。库存已有人工单据号、物流运单号和第三方支付号也需先核对归属及合同约束，不能由Core或Pay重新编号覆盖。

### §9.3 关键源码依据

以下链接用于复核当前实现，行号随后续开发可能变化。

| 需求 | 生成／投影证据 | 页面／契约证据 |
|---|---|---|
| NUM-01～02 | [储值收支投影](../../backend/pay/pay-domain/src/main/java/ai/neargo/shop/pay/storedvalue/service/StoredValueStatementProjector.java)、[内部摘要生成](../../backend/pay/pay-domain/src/main/java/ai/neargo/shop/pay/storedvalue/policy/StoredValueFacts.java) | [收支详情](../../b-app/src/pages/stored-value-record/index.vue)、[旧恢复页](../../b-app/src/pages/stored-value-recharge/LegacyRecharge.vue)、[兼容路由](../../b-app/src/pages/stored-value-recharge/index.vue) |
| NUM-03～04 | [普通权益核销](../../backend/pay/pay-domain/src/main/java/ai/neargo/shop/pay/entitlement/service/EntitlementUseWriter.java)、[储值核销](../../backend/pay/pay-domain/src/main/java/ai/neargo/shop/pay/storedvalue/service/impl/StoredValueRedemptionPostingServiceImpl.java)、[权益发放](../../backend/pay/pay-domain/src/main/java/ai/neargo/shop/pay/entitlement/service/EntitlementGrantWriter.java) | [权益生成助手](../../backend/pay/pay-domain/src/main/java/ai/neargo/shop/pay/entitlement/service/EntitlementRecords.java) |
| NUM-05 | [积分记账](../../backend/pay/pay-domain/src/main/java/ai/neargo/shop/pay/points/posting/impl/PointsPostingServiceImpl.java)、[凭证存储](../../backend/pay/pay-domain/src/main/java/ai/neargo/shop/pay/points/store/impl/MybatisPostingStore.java) | [B端积分记录](../../b-app/src/pages/points-records/index.vue)、[B端对账单及导出](../../b-app/src/pages/statement/index.vue)、[运营查询](../../ops-web/app/points/queries-tab.tsx)、[运营账户分录](../../ops-web/app/points/accounts/page.tsx) |
| NUM-06～07 | [旧券发放](../../backend/pay/pay-domain/src/main/java/ai/neargo/shop/pay/migration/service/LegacyCouponIssueWriter.java)、[迁移阶段](../../backend/pay/pay-domain/src/main/java/ai/neargo/shop/pay/migration/service/impl/EntitlementMigrationServiceImpl.java) | [对账运行和差异](../../backend/pay/pay-domain/src/main/java/ai/neargo/shop/pay/entitlement/service/impl/EntitlementReconciliationServiceImpl.java)、[异常交付及处置](../../backend/pay/pay-domain/src/main/java/ai/neargo/shop/pay/entitlement/service/impl/EntitlementEventDeliveryServiceImpl.java) |
| NUM-08～09 | [库存同步](../../backend/shop-app/src/main/java/ai/neargo/shop/invbridge/StockReconcileService.java)、[库存对齐](../../backend/shop-app/src/main/java/ai/neargo/shop/invbridge/StockAlignmentService.java) | [同步记录](../../b-app/src/pages/stock-sync-records/index.vue)、[对齐批次](../../b-app/src/pages/stock-align/index.vue) |
| NUM-10 | [营销券及批次](../../backend/shop-core/src/main/java/ai/neargo/shop/promotion/service/impl/PmtCouponServiceImpl.java)、[旧营销券](../../backend/shop-core/src/main/java/ai/neargo/shop/marketing/coupon/impl/CouponServiceImpl.java) | [活动创建](../../backend/shop-core/src/main/java/ai/neargo/shop/promotion/service/impl/ActivityServiceImpl.java) |
| NUM-11～12 | [订单号生成器](../../backend/shop-core/src/main/java/ai/neargo/shop/trade/support/OrderNoGenerator.java) | [C端订单列表](../../c-app/src/pages/orders/index.vue)、[支付回跳还原](../../c-app/src/shared/order-no.ts) |
| NUM-13～14 | [提现](../../backend/pay/pay-domain/src/main/java/ai/neargo/shop/pay/service/impl/WithdrawServiceImpl.java)、[结算及开票](../../backend/pay/pay-domain/src/main/java/ai/neargo/shop/pay/impl/SettleServiceImpl.java) | [BizKey](../../backend/shop-base/src/main/java/ai/neargo/shop/common/BizKey.java)、[现有架构守卫](../../backend/shop-app/src/test/java/ai/neargo/shop/arch/BizKeyConventionTest.java) |

### §9.4 共性约束及不应修改的对象

1. **按类型登记，不按前缀猜类型。** 现有命名空间跨层重复，例如Core的EI权益意图与Pay的EI权益实例、Core的EA权益授权与Pay的EA异常动作、Core的MS会员来源与Pay的MS迁移阶段、Core的SL内容位与Pay的SL储值批次。它们不代表已发生同表唯一键冲突，但不允许用通用前缀解析器路由。新增documentType必须携带所属服务和资源类型。
2. **人工单号字段不能覆盖内部字段。** UUID、摘要号长度不同不意味着其唯一性或幂等性失效；保持原有外键、冻结事实、账务摘要、Outbox和重试定位。内部字段的长度继续按原契约，不能统一缩为32位；库存操作号已有96字符入口上限，不因其超过64字符就认定越界。
3. **保持排序和游标语义。** 时间线用业务发生时间及稳定次键；账户顺序用账户序列。对账的原始资源号游标、并发锁定时按内部编号排序属于合法用途，不能替换成人工单号；新增短号也不能改变历史分页边界。
4. **保留安全与外部标识。** 会员码／核销令牌、登录会话、验证码、签名、上传文件名、任务领取令牌、请求幂等键、完整事实摘要不纳入短单号改造。第三方支付、退款、物流、发票等外部编号原样保存，界面分清“业务单号”与“渠道流水号”。
5. **展示、搜索、导出同步设计。** B/C默认展示可理解的业务信息及必要的短号；运营详情保留原始编号复制能力。查单明确支持哪类编号，不能把documentNo传入只接受sourceNo的原接口；CSV按字符串输出，Excel类导出使用文本单元格，历史空映射不填假号。
6. **通知与日志按用途选择。** 后续短信、订阅消息、小票和业务操作审计按需使用documentNo；内部排障继续关联sourceNo、traceId。该项为统一接入要求，本轮没有认定所有通知模板都存在长号缺陷。

### §9.5 合并实施任务与定向验收

本节扩展§8.7的覆盖对象，不要求一次切换全部模块。新前缀只有§8.3已登记项可直接使用，其余先补类型登记及归属评审；不临时拼接EI、EX、RR等旧内部前缀作为新统一规则。Core营销发券批次与Pay实际资产发放批次是两个对象，分别保留映射和关联，Pay不能复用已登记给Core的PI前缀。

| 执行包 | 覆盖需求 | 实施与验收边界 |
|---|---|---|
| A：规范与储值 | NUM-01、02、14及§8基础能力 | 建立Pay号码映射和类型登记，补充值查询／收支详情DTO及B端展示；检查新充值、旧缓存恢复、重复请求、历史空映射、权限和长号查询兼容。资金事务前预留全部号码，避免增加嵌套连接 |
| B：权益与积分凭证 | NUM-03～05 | 两种核销路径、发放批次和积分凭证接入；检查一操作多明细、同请求重试不重复发放／记账、B端与运营显示及导出字段一致 |
| C：迁移、对账及库存 | NUM-06～09 | 按服务分别接入，检查迁移续跑／事件重放不换号、对账游标不变、库存重复同步不重做；不重构资金恢复链路或外部库存协议 |
| D：营销与财务单据 | NUM-10、13 | 接入Core营销人工编号及需要人工查单的财务对象；检查活动／模板／用户券／批次不混淆，第三方编号原样保留 |
| E：订单专项 | NUM-11、12 | 先完成主子单、支付重试及微信回跳合同核对，再升级订单生成器；验收容量边界、时区、子单及重试后缀、旧链接兼容，不能仅改正则或加宽数据库 |

每个执行包完成后先编译及相关基本单测，只回归本包修改和发现的问题；不重复已通过的旧链路，不跑全量测试。本次仅整理需求文档，验证范围为源码依据、链接、格式示例和文档差异；不宣称以上任务已经编码完成。
