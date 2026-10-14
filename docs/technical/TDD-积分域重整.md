# TDD-积分域重整 · 平台积分与资金池

状态：**草稿**（待确认）
档位：**2**（新表族 + 跨域接口 + 不可逆的资金模型决策）
关联需求：[PRD-支付域](../requirements/PRD-支付域.md) §S8（**本 TDD 的 AC 确认后回写 §S8**，现有 AC-8.1/8.3 口径已过时）
资金方案：[积分资金方案-平台预存池](./design/积分资金方案-平台预存池.md) · 决策：[ADR-036](./ADR/ADR-036-积分改为平台出资预存池.md)
创建：2026-10-14 · 最后更新：2026-10-14

> 读法：§2.1 业务抽象 → §2.2 类设计 → §2.3 依赖管理 → §2.4 数据库 → §2.5 接口。
> 「钱为什么这么走」不在这里重复，见资金方案；这里只写**怎么落成代码**。

---

## §0 对账一 · 需求 → 设计

PRD-支付域 §S8 只有 4 条 AC 且口径属于旧模型，下面是按 2026-10-14 运营诉求补写的 AC，确认后整体替换 §S8。

| AC | 需求（Given / When / Then 压成一句） | 落点 |
|---|---|---|
| AC-P1 | 运营创建积分种类，选区域（币种随区域）与兑换比例；生效后比例不可改 | `PointTypeService#create/activate` · `pts_point_type`（`status`、`active_market` 唯一） |
| AC-P2 | 同一区域同一时刻只有一种生效中的平台积分 | `pts_point_type.active_market` 唯一键 |
| AC-P3 | 种类生效前池子必须至少过账过一笔补充 | `PointTypeService#activate` 校验 `pts_fund_pool.topup_total_minor > 0` |
| AC-P4 | 补充 / 提取 / 调账单须由另一人审批后才过账；提取不能超过未分配 | `PoolService#submit/approve` · `pts_pool_request`（审批 SQL 带 `submitted_by <> #{op}`） |
| AC-P5 | 定期补充计划按月生成**待审批**补充单，不自动过账 | `PointsTopupPlanJob` · `pts_topup_plan.last_run_period` 幂等 |
| AC-P6 | 支付成功后按规则发放待生效积分；池子未分配不足时整单不发，记 `SKIPPED/POOL_EXHAUSTED` | `EarnService#grant` · `GrantVoucher` · `pts_grant_position` |
| AC-P7 | 线下单、被暂停、用户冻结、规则为 0 时不发放，并记原因 | `PointsGate` · `pts_grant_position.skip_reason` |
| AC-P8 | 发放子单结算终局后待生效转可用；终局前不可用 | `PointsSettlementHook#onBillFinalized` · `ActivateVoucher` |
| AC-P9 | 下单抵扣：上限 = 可用子单（券后不含运费）× 抵扣比例；只在可用子单间分摊；余额不足降级不抵扣 | `RedeemService#hold` · `DefaultRedeemPolicy` · `HoldVoucher` |
| AC-P10 | 线下单、收单门店有不支持补差的通道、核销暂停时不能抵扣 | `PointsGate` · `DefaultRedeemPolicy` |
| AC-P11 | 取消 / 未成交释放占用；已进入兑付中或已兑付的不可释放 | `RedeemService#release` · `pts_hold_position` 条件更新 |
| AC-P12 | 补差额以积分域当时的占用为准（两阶段）；已释放的不补差 | `PointsSettlementHook#beginRedeem/confirmRedeem/abortRedeem` |
| AC-P13 | 部分退款按累计比例退回抵扣、收回返分；全额退款精确无余数 | `PointsRefundService#apply` · `RefundRatio` |
| AC-P14 | 收回已转正积分时余额不够：不许负，差额从退款现金扣并在退款单明示 | `RevokeVoucher` · `ord_after_sale.points_offset_minor` |
| AC-P15 | 兑付后退款，补差回退失败：用户照退，池子记待追回并告警 | `ReturnVoucher` · `pts_fund_pool.receivable_minor` |
| AC-P16 | 账户闲置满期清零，钱转平台收入；到期前 30 天提醒；核销暂停期间到期顺延 | `ExpiryService` · `ExpireVoucher` · `pts_account.expire_notified_at` |
| AC-P17 | 发放 / 核销可按全局、区域、种类、场景、端、商家、门店、用户分别暂停；暂停只影响将来；必填原因留审计 | `PointsSwitchService` · `pts_switch` · `pts_switch_log` |
| AC-P18 | 未分配低于水位告警；停发告警 | `PoolWatermark` · `OpsAlertPort#pointsAlert` |
| AC-P19 | 统计：平台（区域 × 种类 × 日）、门店（返出 / 收进）、用户（余额与明细） | `PointsStatsService` · `pts_stat_daily` · `pts_voucher` 维度列 |
| AC-P20 | 恒等式 I1–I6 每小时在一致性快照里核对，可定位的差异落差异单，失衡告警 | `PointsReconService` · 对账轴 `POINTS` |
| AC-P21 | 任何积分写入都经凭证过账；同一业务动作重放只生效一次 | `PointsPostingService#post` · `pts_voucher.biz_ref` 唯一 |
| AC-P22 | 售后不能再选「积分退款」形式 | `AfterSaleServiceImpl#approve` 拒 `refundMode=POINTS` · b-app 去掉选项 |

**孤立项**：
- 没落点的 AC：无。
- 挂不上 AC 的设计：`TransferOrder` 划拨单（服务于外部对账 L3，属 AC-P20 的支撑件）；`commission_cap_bp`（本期默认关闭，只留列与接口）。

---

## §1 现状与影响面

### 会被替换的（新模型上线后删除）

| 现有 | 去向 |
|---|---|
| `pay/.../PointsService` · `impl/PointsServiceImpl`（1100 行，读写混在一起） | 拆为 §2.2 的 `points.app.*` 若干应用服务 + `PointsPostingService` |
| `pay/.../PointsConfig`（JSON 配置，比例可随时改） | `PointType` 主数据 |
| `pay/.../port/PointsPortImpl` · `shop-base/spi/settle/PointsPort` | `spi/points/PointsEarnPort` · `PointsRedeemPort` · `PointsRefundPort` |
| `pay/.../job/PointsActivateJob` · `PointsIdentityJob` | 事件驱动转正 + `ActivateSweeper`；`PointsReconJob` |
| `pay/.../service/recon/PointsPoolReconAxis` | 改由 `PointsReconService` 提供（轴码 `POINTS_POOL` → `POINTS`） |
| `payclient/PointsPolicyAppService`（端策略 JSON） | `pts_switch` 的 `CLIENT` 粒度 |
| 表 `pts_user_account` · `pts_user_ledger` · `stl_points_pool` | 冻结只读；迁移验证后删（另开迁移） |

### 会被改到的（已在跑的功能）

| 位置 | 改动 |
|---|---|
| `OrderServiceImpl` 下单 | `pointsPort.deduct` → `PointsRedeemPort#hold`；**挪到「线下禁平台券」校验之后**（现状先扣分后校验，被拒的单要等 I6 才还分） |
| `OrderServiceImpl` 支付后 | `grantPointsAfterPay` → `PointsEarnPort#grant`；不再写 `points_fee_minor` / `points_granted` |
| `OrderServiceImpl` 取消 / 关闭 | `pointsPort.reverse` → `PointsRedeemPort#release` |
| `AfterSaleServiceImpl#doRefund` | ① 回退分账 → **② `PointsRefundPort#apply`** → ③ 退现金（退款额 − 现金抵扣）；删 `returnBenefitsIfWholeOrderRefunded` 里的积分部分；`approve` 拒 `POINTS` 退款形式 |
| `SettleServiceImpl` 生成结算单 | 不再记 `MERCHANT_RECEIVE`；不再算 `points_fee_minor` |
| `SettleServiceImpl#executeSplit` | 补差前 `beginRedeem`，成功 `confirmRedeem`、失败 `abortRedeem`；`SPLIT_CONFIRMED` 时 `onBillFinalized` |
| `SettleServiceImpl` / `PayoutServiceImpl` 归集确认与付款 | 删 `confirmDeduction`；归集在 `CONFIRMED` 时一次完成兑付 + 转正 |
| `SettleServiceImpl#reverseSplit` | 补差回退结果经 `onSubsidyReturn` 告知积分域 |
| `FundInvariantReconciler` I3 / I6 | I3（标记有流水无）删除；I6（释放死单占用）改读 `pts_hold_position` |
| `BootstrapConfigServiceImpl` | `features.points` 改由后端闸门求值下发，不再读 yml 常量 |
| `MerchantPortImpl#pointsDenyReason` / `isPointsForced` · `mch_entity.points_enabled/forced` | 退役；改运营黑名单（`pts_switch` 的 `MERCHANT` / `STORE` 粒度） |
| 三端界面 | c-app 积分页 / 结算页；b-app 售后去掉「积分」形式、积分页改为本店统计；ops-web 新增 6 页（见 §2.5.4）→ **要更新 `ui-catalog.json`** |

### 可直接复用

| 构件 | 用在哪 |
|---|---|
| `MarketService`（区域、币种、小数位、时区） | 种类创建取币种；到期取区域时区 |
| `BizKey` | 凭证号、流水号、单据号 |
| `AfterCommit` · Outbox | 跨域写推到提交之后；`confirmRedeem` 走 outbox 防丢 |
| `RefundLedger` 的条件增量写法与 `ord_sub_order.refunded_minor` | 退款比例 k 的分子直接读它 |
| `OpsAlertPort` | 告警（新增一个按事给的方法 `pointsAlert`） |
| `ReconAxis` + `stl_recon_diff` | 差异单落 `axis='POINTS'` |
| `JobSupport` / `JobDeclaration` / ShedLock | 七个任务 |
| `PointsRulePort`（类目规则 + 商品例外） | 返分规则；`FIXED` 改为乘件数 |

### 明确不受影响的

券、运费、分账主流程（除补差那一步）、库存、物流、进销存、商家资质。

---

## §2 方案

### 2.1 业务抽象

![积分域对象模型](./diagrams/pts2-domain-model.svg)

#### 统一语言

| 词 | 定义 | 不是什么 |
|---|---|---|
| **区域** Market | 资金口径的隔离单位：币种、小数位、时区。取自 `MarketService` | 不是配送范围、不是社区 |
| **积分种类** PointType | 一套独立核算的积分：属于一个区域，有出资方、兑换比例、抵扣比例、闲置天数 | 不是「积分活动」—— 活动是场景，种类是账本 |
| **出资方** Funder | 谁的钱进池。本期只有 `PLATFORM` | — |
| **场景** Scene | 积分从哪来：`SHOPPING_REBATE`（本期）、`SIGN_IN` 等（预留） | 场景不决定账本，种类才决定 |
| **资金池** FundPool | 一个种类一个；三格 U / C / R | 不是独立银行账户（本期是平台账户内专项） |
| **积分账户** PointsAccount | 用户 × 种类；三格 P / A / H | 不跨种类合并显示余额 |
| **头寸** Position | 一张子单在一个种类下的累计数：发放头寸（发了多少、转正多少、收回多少）、占用头寸（占了、释放、兑付、退回多少） | 不是流水 —— 流水只增不改，头寸是可条件累加的计数器 |
| **凭证** Voucher | 一次业务动作的**写入单元**：列出要动的账户格、池子格、头寸，校验平衡后一个事务落下 | 不是第四本账 |
| **流水** LedgerEntry / PoolFlow | 凭证落下后，账户与池子每一格的变动记录，带变动后值 | 不能被单独写 |
| **结算终局** | 直连：分账回执 `SPLIT_CONFIRMED`；归集：结算单 `CONFIRMED` | 不是「支付 + N 天」 |
| **划拨单** TransferOrder | 按日把同类池子流水汇成一笔真钱动作（到期转收入、归集兑付回补、补充、提取） | 直连补差不走划拨单（逐单走通道，记在 `stl_split_log`） |

#### 不变式（谁守、在哪守）

| # | 不变式 | 守在哪 |
|---|---|---|
| V1 | 每张凭证：`Δ(C + R) == m(Δ(P + A + H))` | `Voucher` 构造时校验（纯内存） |
| V2 | 每张凭证：`Δ(U + C) == external_minor`，且方向是该凭证类型允许的 | `Voucher` 构造时校验 |
| V3 | 所有格 ≥ 0 | 条件更新 SQL（`... AND col + #{d} >= 0`），0 行 → 整张回滚 |
| V4 | 头寸累计不超上限（释放 + 兑付 ≤ 占用；转正 + 收回 ≤ 发放；退回 ≤ 兑付） | 头寸条件更新 SQL |
| V5 | 同一业务动作只过账一次 | `pts_voucher.biz_ref` 唯一键；重复即返回已有结果 |
| V6 | 积分数是 `points_unit` 的整数倍 | `RedeemRate#requireAligned`；所有策略输出先 `floorAligned` |
| V7 | 只有 `PointsPostingService` 能写账户 / 池子 / 头寸 / 流水 | ArchUnit（§2.3） |

V1 + V2 推出资金方案里的总恒等式 `C + R == m(P + A + H)`：每张凭证都保持它，于是任何时刻都保持它；
I1 巡检只用来抓**绕过凭证**的带外写。

凭证类型允许的外部资金方向（V2）：

| 方向 | 凭证类型 |
|---|---|
| **进**（external > 0） | `TOPUP`、`RECOVER` |
| **出**（external < 0） | `WITHDRAW`、`REDEEM`、`EXPIRE` |
| **进或无** | `RETURN`（补差回退成功为进；失败记 R、为无）· `REVOKE`（有现金抵扣为进；否则为无） |
| **无**（external = 0） | `GRANT`、`ACTIVATE`、`HOLD`、`RELEASE`、`ADJUST`、`PROMO_GRANT` |

### 2.2 类设计

#### 包结构

全部在 `pay/pay-domain`（积分是资金域知识，与结算共用 `payTxManager`），新包 `ai.neargo.shop.pay.points`：

```text
pay.points
├── model/            值对象与快照 —— 纯 Java，不依赖 Spring / MyBatis
├── voucher/          凭证模板：从「命令 + 当前快照」构造一张已校验平衡的 Voucher
├── policy/           规则策略（接口 + Default 实现）：返多少、能抵多少、退多少、闸门、到期
├── posting/          PointsPostingService —— 唯一写入口
├── store/            持久化端口（接口）
│   └── impl/         MyBatis 实现（只被 posting 与 app 的读路径用）
├── app/              应用服务：编排 gate → policy → voucher → post；也是 port / controller 的唯一调用面
├── recon/            I1–I6 与外部对账
├── job/              7 个任务的触发器
└── port/             spi 实现：PointsEarnPortImpl / PointsRedeemPortImpl / PointsRefundPortImpl
pay.mapper.points     Mapper（必须在 pay.mapper 下，才会被 paySqlSessionFactory 扫到）
```

#### model（值对象）

| 类 | 职责 | 关键成员 |
|---|---|---|
| `PointTypeCode` | 种类编码 | `record(String value)` |
| `PointType` | 种类快照 | `code, market, currency, scale, funder, rate, deductRatioBp, idleDays, remindDays, status` |
| `RedeemRate` | 既约兑换比例 | `of(points, minor)`（约分）· `toMinor(pts)`（要求对齐）· `floorAligned(pts)` · `pointsFor(minor)` |
| `Bucket` / `PoolBucket` | `PENDING/AVAILABLE/HELD` · `UNALLOCATED/COMMITTED/RECEIVABLE` | — |
| `VoucherType` | 13 种凭证 | `ExternalFlow allowed()`（IN / OUT / NONE / IN_OR_NONE） |
| `Scene` | `SHOPPING_REBATE`、`SIGN_IN`（预留）、`ADJUST` | — |
| `BizRef` | 幂等键 | `record(VoucherType type, String ref)` · `key()` = `type:ref` |
| `Dims` | 统计维度 | `market, scene, userNo, orderNo, subOrderNo, entityNo, storeNo, operatorNo, reason` |
| `AccountMove` / `PoolMove` | 一格的变动 | `(userNo, Bucket, deltaPoints)` · `(PoolBucket, deltaMinor, counterparty)` |
| `PositionMove` | 头寸变动（sealed） | `GrantOpen / Activate / RevokePending / RevokeAvailable / HoldOpen / Release / BeginRedeem / ConfirmRedeem / AbortRedeem / Return` |
| `Voucher` | 已校验的写入单元 | 构造时校验 V1 / V2 / V6，不平即抛 `UnbalancedVoucher` |
| `AccountSnapshot` / `PoolSnapshot` / `GrantPosition` / `HoldPosition` | 读出来的当前状态，供策略计算 | — |
| `HoldState` | `HELD → REDEEMING → REDEEMED → CLOSED` | `canRelease()` · `canBeginRedeem()` |

```java
/** 一次业务动作的写入单元。构造即校验 —— 拿到实例就一定是平的。 */
public record Voucher(BizRef bizRef, PointType type, Dims dims, long externalMinor,
                      List<AccountMove> accountMoves, List<PoolMove> poolMoves,
                      List<PositionMove> positionMoves) {
    public Voucher {
        accountMoves = List.copyOf(accountMoves);
        poolMoves = List.copyOf(poolMoves);
        positionMoves = List.copyOf(positionMoves);
        long liabilityPts = accountMoves.stream().mapToLong(AccountMove::deltaPoints).sum();
        long backing = sumOf(poolMoves, COMMITTED) + sumOf(poolMoves, RECEIVABLE);
        if (backing != type.rate().toMinor(liabilityPts)) {           // V1（toMinor 内含 V6）
            throw new UnbalancedVoucher(bizRef, "backing", backing, liabilityPts);
        }
        long cash = sumOf(poolMoves, UNALLOCATED) + sumOf(poolMoves, COMMITTED);
        if (cash != externalMinor || !bizRef.type().allowed().permits(externalMinor)) {  // V2
            throw new UnbalancedVoucher(bizRef, "external", cash, externalMinor);
        }
    }
}
```

#### voucher（凭证模板）

每种凭证一个工厂方法，集中在 `VoucherFactory`（不拆 13 个类 —— 每个只有十来行，拆开反而难对照）：

```java
public final class VoucherFactory {
    Voucher topup(PointType t, long minor, String requestNo, Dims d);
    Voucher withdraw(PointType t, long minor, String requestNo, Dims d);
    Voucher grant(PointType t, String subOrderNo, long points, Dims d);             // P+, U−, C+
    Voucher activate(PointType t, GrantPosition g, Dims d);                          // P−, A+
    Voucher hold(PointType t, String orderNo, List<HoldShare> shares, Dims d);      // A−, H+（逐子单头寸）
    Voucher release(PointType t, HoldPosition h, long points, String ref, Dims d);   // H−, A+
    Voucher redeem(PointType t, HoldPosition h, String settleNo, Dims d);           // H−, C−（出）
    Voucher returnBack(PointType t, HoldPosition h, long points, boolean funded, String ref, Dims d); // A+, C+ 或 R+
    Voucher revoke(PointType t, GrantPosition g, RevokeSplit split, String ref, Dims d); // P−/A−, C−, U+（含现金抵扣）
    Voucher expire(PointType t, AccountSnapshot a, Dims d);                          // A−, C−（出）
    Voucher adjust(PointType t, String userNo, long signedPoints, String requestNo, Dims d);
    Voucher recover(PointType t, long minor, String ref, Dims d);                    // R−, C+（进）
}
```

#### policy（规则策略，接口 + Default，不碰库）

| 接口 | Default 规则 | 输入 → 输出 |
|---|---|---|
| `EarnPolicy` | 每行：`FIXED` = n × 件数；`RATIO` = 行实付 × 万分比；赠品 0；合计后 `floorAligned` | `List<EarnLine>, PointType` → `long points` |
| `CommissionCapPolicy` | 本期 `NoCap`（直接返回原值）；`commission_cap_bp` 非空时截断 | `points, commissionMinor, PointType` → `long` |
| `RedeemPolicy` | 上限 = Σ可用子单金额 × `deductRatioBp`，与余额、意愿值取小；按金额分摊、余数给最后一家；`floorAligned` | `HoldQuery, AccountSnapshot, eligibleTargets` → `HoldPlan` |
| `RefundRatioPolicy` | 累计比例：`target = floorAligned(base × refunded / payBasis)`，`delta = target − done`；全退取精确 | `GrantPosition/HoldPosition, refundedTotalMinor` → `RefundSplit` |
| `ExpiryPolicy` | `expireAt = 区域本地日 23:59:59 (now + idleDays)`；`remindAt = expireAt − remindDays` | `PointType, now` → 时刻 |
| `PointsGate` | 按 全局 → 区域 → 种类 → 场景 → 端 → 商家 → 门店 → 用户 依次求值，首个命中即拒；另判线下、通道补差能力 | `GateQuery(op=ISSUE/REDEEM, …)` → `GateDecision(allowed, reasonCode)` |

#### posting（唯一写入口）

```java
public interface PointsPostingService {
    /**
     * 过账。同一事务内按固定顺序：凭证头（抢 biz_ref）→ 头寸 → 账户 → 池子 → 流水。
     * 任一条件更新 0 行 → 抛 PostingRejected(哪个对象、哪条约束)，整张回滚。
     * biz_ref 已存在 → 不重复写，返回已有凭证（replayed = true）。
     */
    PostingResult post(Voucher voucher);

    record PostingResult(String voucherNo, boolean replayed) {}
}
```

**锁顺序固定**为「头寸 → 账户 → 池子」：池子行最热，放最后让它的锁持有时间最短；固定顺序防死锁。

#### store（持久化端口，全部是条件写）

| 接口 | 写方法（只被 posting 调） | 读方法 |
|---|---|---|
| `PointTypeStore` | `insert` · `activate(code, op)`（`active_market` 唯一）· `retire` · `updateDraft` | `find` · `activeOf(market)` · `list` |
| `AccountStore` | `ensure(userNo, type)`（`INSERT IGNORE` 语义）· `apply(userNo, type, dP, dA, dH, now, renewExpireAt)` → boolean | `find` · `dueToExpire(type, now, limit)` · `dueToRemind` |
| `PoolStore` | `apply(type, dU, dC, dR, totals)` → boolean | `find` |
| `PositionStore` | `openGrant` · `openSkipped` · `activate` · `revokePending` · `revokeAvailable` · `openHold` · `release` · `beginRedeem` · `confirmRedeem` · `abortRedeem` · `returnBack` · `markRefundFunded` —— 全部返回 boolean | `grantOf` · `holdOf` · `holdsOfOrder` · `stuckRedeeming` · `finalizedNotActivated` |
| `VoucherStore` | `insertIfAbsent(voucher)` → `Optional<已有凭证号>` | 按 biz_ref / 维度查 |
| `LedgerStore` / `PoolFlowStore` | `append(...)` | 分页明细 |
| `SwitchStore` · `PoolRequestStore` · `TopupPlanStore` · `TransferOrderStore` · `StatStore` · `CursorStore` | 各自条件写 | 各自查询 |

#### app（应用服务 —— 编排，不含规则）

| 服务 | 方法 | 编排 |
|---|---|---|
| `PointTypeService` | `create` · `updateDraft` · `activate` · `retire` · `list` | 校验 → store |
| `PoolService` | `submit(PoolRequestCmd)` · `approve(no, op)` · `reject(no, op, remark)` · `runPlans(now)` · `watermark(type)` | 审批 → `VoucherFactory.topup/withdraw/adjust` → post；`COMMISSION_RATIO` 计划经 `SettleStatsService`（接口，只读）取上月佣金 —— 依赖接口不依赖结算实现（A5） |
| `PointsSwitchService` | `set(scope, issuePaused, redeemPaused, reason, op)` · `list` · `logs` | 写开关 + 日志；恢复核销时调 `ExpiryService#extendForPause` |
| `EarnService` | `grant(GrantCommand)` · `activateFinalized(subOrderNo)` | gate → `EarnPolicy` → `CommissionCapPolicy` → `grant` 凭证 → post；`PostingRejected(POOL)` → `openSkipped` + 告警 |
| `RedeemService` | `quote` · `hold` · `release` · `beginRedeem` · `confirmRedeem` · `abortRedeem` | gate → `RedeemPolicy` → 凭证 → post |
| `PointsRefundService` | `quote(subOrderNo, refundedTotalAfter)` · `apply(afterSaleNo, subOrderNo, refundedTotalAfter)` · `onSubsidyReturn(subOrderNo, minor, ok)` | `RefundRatioPolicy` → `release/returnBack/revoke` 凭证 → post；返回现金抵扣额 |
| `ExpiryService` | `expireDue(type)` · `remindDue(type)` · `extendForPause(type, ms)` | 循环批量，每条一张 `expire` 凭证（单条失败不影响其余） |
| `TransferService` | `buildDaily(type, date)` · `execute(transferNo)` | 汇总池子流水 → 划拨单 → `TransferExecutor`（本期唯一实现 `InternalBookingExecutor`） |
| `PointsQueryService` | `account(userNo, market)` · `records` · `storeSummary` · `storeRecords` | 只读 |
| `PointsStatsService` | `rollup()`（按水位增量）· `platform(...)` · `stores(...)` · `users(...)` | 只读 + 汇总表 |
| `PointsSettlementHook` | `beginRedeem` · `confirmRedeem` · `abortRedeem` · `onBillFinalized` · `onSubsidyReturn` | 给结算域调的**域内**入口（同模块，不走 spi） |

#### recon / job

| 类 | 内容 |
|---|---|
| `PointsReconService` | `internal(type)`：一致性快照里核 I1–I6，可定位的落 `stl_recon_diff(axis='POINTS')`；`external(type, date)`：账户余额与补差流水 |
| `PointsReconAxis` | 实现既有 `ReconAxis`，接进对账总览 |
| 任务 | `PointsExpireJob`（日，区域时区 00:35）· `PointsRemindJob`（日）· `PointsReconJob`（时）· `PointsStatJob`（5 分钟）· `PointsTransferJob`（日）· `RedeemingSweeper`（10 分钟，超 30 分钟告警）· `ActivateSweeper`（时，补漏转正）· `PointsTopupPlanJob`（日） |

#### 写一笔发放的全过程（示意，说明各层分工）

```java
// app/EarnService —— 只编排，规则都在 policy，平衡都在 Voucher，写都在 posting
public GrantOutcome grant(GrantCommand cmd) {
    PointType t = types.activeOf(cmd.market()).orElse(null);
    if (t == null) return GrantOutcome.skipped(NO_ACTIVE_TYPE);
    GateDecision g = gate.check(GateQuery.issue(t, cmd));                 // 暂停 / 线下 / 冻结
    if (!g.allowed()) return skip(t, cmd, g.reason(), 0);
    long pts = cap.apply(earn.pointsFor(cmd.lines(), t), cmd.commissionMinor(), t);
    if (pts <= 0) return skip(t, cmd, RULE_ZERO, 0);
    try {
        posting.post(vouchers.grant(t, cmd.subOrderNo(), pts, dimsOf(cmd)));
        return GrantOutcome.granted(pts);
    } catch (PostingRejected e) {
        if (e.on(POOL_UNALLOCATED)) { alert.poolExhausted(t); return skip(t, cmd, POOL_EXHAUSTED, pts); }
        throw e;
    }
}
```

### 2.3 依赖管理

![积分域模块依赖](./diagrams/pts2-modules.svg)

#### 跨模块：谁调谁

| 调用方 | 被调 | 方式 | 事务 |
|---|---|---|---|
| trade（`shop-core`）下单 / 取消 | `PointsRedeemPort` | spi 同步调用 | 积分走 `payTxManager`，独立提交；下单失败由 `FundInvariantReconciler` I6 释放死单占用 |
| trade 支付提交后 | `PointsEarnPort#grant` | `AfterCommit` | 同上；幂等由 `biz_ref` 保证 |
| trade 售后退款 | `PointsRefundPort#apply` | 在「回退分账」与「退现金」之间同步调用 | 积分先落定，返回现金抵扣额；现金失败重试不会重复扣 |
| trade → product | `PointsRulePort` | spi | 取类目规则 + 商品例外，**组成 `EarnLine` 传给 pay**，pay 不回查 product |
| settle（同模块） | `PointsSettlementHook` | 直接注入接口 | 与结算单同一个 `payTxManager` 事务；`confirmRedeem` 另经 outbox 兜底 |
| points → notify | `OpsAlertPort#pointsAlert` · 到期提醒走 `UserInboxPort` / `WxSubscribePort` | spi | 尽力而为，不抛 |
| points → `MarketService` | 同模块 | 直接注入 | 只读 |
| shop-app Controller | `payclient/*AppService` → `points.app.*` | 与现有 `BizPointsAppService` 同一形状 | — |

**points 不依赖 settle**：方向只有 settle → points（经 `PointsSettlementHook`）。需要结算事实时由 settle **推**进来（`onBillFinalized`、`onSubsidyReturn`），积分域把它落在头寸上（`finalized_at`、`refund_funded_minor`），不反查结算单 —— 反查会在包级形成环。

#### 包内分层规则（新增 ArchUnit，放 `ArchitectureTest`）

| # | 规则 | 防住什么 |
|---|---|---|
| A1 | `pay.points.model..` 不依赖 Spring、MyBatis、`pay.points` 的其他包 | 值对象可纯单测；规则不被持久化细节污染 |
| A2 | `pay.points.voucher..`、`pay.points.policy..` 只依赖 `model` | 策略不碰库 —— 「先查后写」从结构上写不出来 |
| A3 | `store` 的写方法（`apply/open*/activate/revoke*/release/*Redeem/returnBack/append/insertIfAbsent`）**只能被 `posting..` 调用** | review 里那一整类「改了一边忘了另一边」 |
| A4 | `pay.mapper.points..` 只能被 `pay.points.store.impl..` 引用 | 绕过 store 直接写表 |
| A5 | `pay.points..` 不得依赖 `pay.impl.SettleServiceImpl` 等结算实现 | 包级环 |
| A6 | `shop-core` 只能经 `spi.points.*` 调积分 | 跨域直连 |
| A7 | 旧 `PointsService` / `PointsPort` 删除后加一条：不存在名为 `PointsService` 的类型 | 旧入口复活 |

`CrossDomainTxConventionTest` 已要求 pay 下的 `@Transactional` 点名 `payTxManager`，新代码沿用。

### 2.4 数据库

> 表关系图属于结构图，按[文档规范](../文档规范.md) §二 必须从 schema 生成：迁移落地后由 `npm run gen:erd` 产出，此处不手画。
> 关系一句话：种类 1—1 池子、1—N 账户；凭证 1—N 积分流水 / 池子流水，按 `sub_order_no` 关联头寸；
> 补充单 → 凭证；划拨单 ← 池子流水（`transfer_no`）；日汇总从凭证重算。

#### 表清单

| 表 | 对象 | 行数量级 | 写入者 |
|---|---|---|---|
| `pts_point_type` | 积分种类 | 每区域个位数 | `PointTypeStore` |
| `pts_fund_pool` | 资金池（一种类一行） | 同上 | posting |
| `pts_pool_flow` | 池子流水 | 每凭证 0–2 行 | posting |
| `pts_account` | 用户账户 | 用户数 × 种类 | posting |
| `pts_ledger_entry` | 积分流水（用户可见） | 每凭证 0–2 行 / 账户 | posting |
| `pts_voucher` | 凭证头（含全部统计维度） | 每个动作 1 行 | posting |
| `pts_grant_position` | 发放头寸（含停发记录） | 每支付子单 1 行 | posting |
| `pts_hold_position` | 占用头寸 | 每用分子单 1 行 | posting |
| `pts_pool_request` | 补充 / 提取 / 调账单 | 低频 | `PoolService` |
| `pts_topup_plan` | 定期补充计划 | 每种类 0–1 | `PoolService` |
| `pts_switch` · `pts_switch_log` | 暂停开关与历史 | 低频 | `PointsSwitchService` |
| `pts_transfer_order` | 划拨单 | 每种类每日数行 | `TransferService` |
| `pts_stat_daily` | 日汇总 | 日 × 种类 × 门店 × 类型 | `PointsStatsService` |
| `pts_cursor` | 水位（统计、I2） | 个位数 | 任务 |

#### DDL（`V406__points_v2_schema.sql`；不写 ENGINE / CHARSET，见 prod-mysql97 约定）

```sql
-- 积分种类：一区域同时只有一种生效。active_market 只在 ACTIVE 时等于 market、否则 NULL，
-- 唯一键对 NULL 不生效 —— 用它代替「UPDATE … WHERE NOT EXISTS(同表子查询)」（MySQL 1093）
CREATE TABLE IF NOT EXISTS pts_point_type (
    id                BIGINT(20)  NOT NULL AUTO_INCREMENT,
    type_code         VARCHAR(32) NOT NULL COMMENT '种类编码，如 CN_PLATFORM',
    name              VARCHAR(64) NOT NULL COMMENT '默认名称',
    name_i18n         VARCHAR(512) DEFAULT NULL COMMENT '多语名称 JSON：{"en":"…","ar":"…"}',
    funder            VARCHAR(16) NOT NULL DEFAULT 'PLATFORM' COMMENT '出资方：PLATFORM（本期唯一）',
    market            VARCHAR(8)  NOT NULL COMMENT '区域，取自市场主数据',
    currency          VARCHAR(8)  NOT NULL COMMENT '对标币种，创建时取区域币种，之后不可改',
    currency_scale    INT(11)     NOT NULL COMMENT '币种小数位快照',
    rate_points       BIGINT(20)  NOT NULL COMMENT '兑换比例（既约）：rate_points 积分 = rate_minor 最小货币单位',
    rate_minor        BIGINT(20)  NOT NULL,
    deduct_ratio_bp   INT(11)     NOT NULL COMMENT '抵扣上限，万分比（3000 = 30%）',
    idle_days         INT(11)     NOT NULL COMMENT '闲置多少天清零',
    remind_days       INT(11)     NOT NULL DEFAULT 30 COMMENT '到期前多少天提醒',
    commission_cap_bp INT(11)     DEFAULT NULL COMMENT '单笔返分 ≤ 佣金 × 本值；NULL = 不封顶（本期默认）',
    status            VARCHAR(12) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT / ACTIVE / RETIRED；比例只在 DRAFT 可改',
    active_market     VARCHAR(8)  DEFAULT NULL COMMENT 'ACTIVE 时 = market，否则 NULL —— 唯一键保证一区域一种',
    successor_code    VARCHAR(32) DEFAULT NULL COMMENT '停用后余额迁往哪个种类（改比例 = 换种类）',
    activated_at      BIGINT(20)  DEFAULT NULL,
    retired_at        BIGINT(20)  DEFAULT NULL,
    tenant_no VARCHAR(32) DEFAULT NULL, created_at DATETIME DEFAULT NULL, created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME DEFAULT NULL, updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0, deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_pts_type_code (type_code),
    UNIQUE KEY uk_pts_type_active_market (active_market)
) COMMENT='积分种类：一套独立核算的积分（区域 + 币种 + 兑换比例）';

-- 资金池：一种类一行。三格各自条件更新不许为负
CREATE TABLE IF NOT EXISTS pts_fund_pool (
    id                   BIGINT(20)  NOT NULL AUTO_INCREMENT,
    type_code            VARCHAR(32) NOT NULL,
    currency             VARCHAR(8)  NOT NULL,
    unallocated_minor    BIGINT(20)  NOT NULL DEFAULT 0 COMMENT 'U 未分配：补进来、未承诺给任何积分的钱',
    committed_minor      BIGINT(20)  NOT NULL DEFAULT 0 COMMENT 'C 已承诺：已发积分（含待生效）对应的钱',
    receivable_minor     BIGINT(20)  NOT NULL DEFAULT 0 COMMENT 'R 待追回：该回池、钱还在外面（补差回退失败）',
    topup_total_minor    BIGINT(20)  NOT NULL DEFAULT 0,
    withdraw_total_minor BIGINT(20)  NOT NULL DEFAULT 0,
    redeem_total_minor   BIGINT(20)  NOT NULL DEFAULT 0,
    expire_total_minor   BIGINT(20)  NOT NULL DEFAULT 0,
    low_water_days       INT(11)     NOT NULL DEFAULT 7 COMMENT '可发天数低于此值告警',
    tenant_no VARCHAR(32) DEFAULT NULL, created_at DATETIME DEFAULT NULL, created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME DEFAULT NULL, updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0, deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_pts_pool_type (type_code)
) COMMENT='积分资金池（平台账户内专项）。U + C 与平台账户余额每日外部对账';

CREATE TABLE IF NOT EXISTS pts_pool_flow (
    id                BIGINT(20)  NOT NULL AUTO_INCREMENT,
    flow_no           VARCHAR(40) NOT NULL,
    voucher_no        VARCHAR(40) NOT NULL,
    voucher_type      VARCHAR(16) NOT NULL,
    type_code         VARCHAR(32) NOT NULL,
    bucket            VARCHAR(12) NOT NULL COMMENT 'UNALLOCATED / COMMITTED / RECEIVABLE',
    delta_minor       BIGINT(20)  NOT NULL COMMENT '带符号',
    after_minor       BIGINT(20)  NOT NULL COMMENT '该格变动后余额（与池子行同一事务读出）',
    counterparty_type VARCHAR(16) DEFAULT NULL COMMENT 'MERCHANT / PLATFORM / FINANCE',
    counterparty_no   VARCHAR(64) DEFAULT NULL,
    transfer_no       VARCHAR(40) DEFAULT NULL COMMENT '被哪张划拨单覆盖（日汇总后回填）',
    occurred_at       BIGINT(20)  NOT NULL,
    tenant_no VARCHAR(32) DEFAULT NULL, created_at DATETIME DEFAULT NULL, created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME DEFAULT NULL, updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0, deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_pts_pool_flow_no (flow_no),
    KEY idx_pts_pool_flow_type (type_code, id),
    KEY idx_pts_pool_flow_voucher (voucher_no),
    KEY idx_pts_pool_flow_transfer (transfer_no)
) COMMENT='池子流水：只增不改，只由过账写';

CREATE TABLE IF NOT EXISTS pts_account (
    id                 BIGINT(20)  NOT NULL AUTO_INCREMENT,
    user_no            VARCHAR(64) NOT NULL,
    type_code          VARCHAR(32) NOT NULL,
    pending_points     BIGINT(20)  NOT NULL DEFAULT 0 COMMENT 'P 待生效：可见不可用',
    available_points   BIGINT(20)  NOT NULL DEFAULT 0 COMMENT 'A 可用：不许为负',
    held_points        BIGINT(20)  NOT NULL DEFAULT 0 COMMENT 'H 占用：下单已扣、未兑付',
    frozen             TINYINT(4)  NOT NULL DEFAULT 0 COMMENT '风控冻结：不发不用，在途照常',
    last_active_at     BIGINT(20)  DEFAULT NULL COMMENT '最近一次发放或使用',
    expire_at          BIGINT(20)  DEFAULT NULL COMMENT '账户到期时刻（区域本地日 23:59:59）',
    expire_notified_at BIGINT(20)  DEFAULT NULL COMMENT '到期提醒幂等位；续期时清空',
    earned_total       BIGINT(20)  NOT NULL DEFAULT 0,
    used_total         BIGINT(20)  NOT NULL DEFAULT 0,
    revoked_total      BIGINT(20)  NOT NULL DEFAULT 0,
    expired_total      BIGINT(20)  NOT NULL DEFAULT 0,
    tenant_no VARCHAR(32) DEFAULT NULL, created_at DATETIME DEFAULT NULL, created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME DEFAULT NULL, updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0, deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_pts_account_user_type (user_no, type_code),
    KEY idx_pts_account_expire (type_code, expire_at)
) COMMENT='用户积分账户（用户 × 种类）。三格只由过账条件更新';

CREATE TABLE IF NOT EXISTS pts_ledger_entry (
    id           BIGINT(20)  NOT NULL AUTO_INCREMENT,
    entry_no     VARCHAR(40) NOT NULL,
    voucher_no   VARCHAR(40) NOT NULL,
    voucher_type VARCHAR(16) NOT NULL COMMENT '决定用户看到的标题（i18n：points.voucher.{type}）',
    type_code    VARCHAR(32) NOT NULL,
    user_no      VARCHAR(64) NOT NULL,
    bucket       VARCHAR(12) NOT NULL COMMENT 'PENDING / AVAILABLE / HELD',
    delta_points BIGINT(20)  NOT NULL COMMENT '带符号',
    after_points BIGINT(20)  NOT NULL COMMENT '该格变动后余额',
    order_no     VARCHAR(40) DEFAULT NULL,
    sub_order_no VARCHAR(40) DEFAULT NULL,
    occurred_at  BIGINT(20)  NOT NULL,
    tenant_no VARCHAR(32) DEFAULT NULL, created_at DATETIME DEFAULT NULL, created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME DEFAULT NULL, updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0, deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_pts_entry_no (entry_no),
    KEY idx_pts_entry_user (user_no, type_code, id),
    KEY idx_pts_entry_voucher (voucher_no)
) COMMENT='积分流水（用户可见）：只增不改，只由过账写';

-- 凭证头：幂等键 + 全部统计维度。明细统计都从这里出，不另记第二份
CREATE TABLE IF NOT EXISTS pts_voucher (
    id             BIGINT(20)  NOT NULL AUTO_INCREMENT,
    voucher_no     VARCHAR(40) NOT NULL,
    biz_ref        VARCHAR(96) NOT NULL COMMENT '幂等键 = 凭证类型:业务单号，如 GRANT:S2026…',
    voucher_type   VARCHAR(16) NOT NULL,
    type_code      VARCHAR(32) NOT NULL,
    market         VARCHAR(8)  NOT NULL,
    currency       VARCHAR(8)  NOT NULL,
    rate_points    BIGINT(20)  NOT NULL COMMENT '过账时的兑换比例快照',
    rate_minor     BIGINT(20)  NOT NULL,
    points         BIGINT(20)  NOT NULL DEFAULT 0 COMMENT '本凭证的主分数（绝对值）',
    amount_minor   BIGINT(20)  NOT NULL DEFAULT 0 COMMENT '主分数折算金额',
    external_minor BIGINT(20)  NOT NULL DEFAULT 0 COMMENT '进出池子的真钱：进为正、出为负',
    scene          VARCHAR(24) NOT NULL,
    user_no        VARCHAR(64) DEFAULT NULL,
    order_no       VARCHAR(40) DEFAULT NULL,
    sub_order_no   VARCHAR(40) DEFAULT NULL,
    entity_no      VARCHAR(64) DEFAULT NULL COMMENT '商家主体（发放方或收单方，看凭证类型）',
    store_no       VARCHAR(64) DEFAULT NULL COMMENT '门店（发放门店或收单门店）',
    request_no     VARCHAR(40) DEFAULT NULL COMMENT '补充 / 提取 / 调账单号',
    operator_no    VARCHAR(64) DEFAULT NULL,
    reason         VARCHAR(255) DEFAULT NULL,
    occurred_at    BIGINT(20)  NOT NULL,
    tenant_no VARCHAR(32) DEFAULT NULL, created_at DATETIME DEFAULT NULL, created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME DEFAULT NULL, updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0, deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_pts_voucher_no (voucher_no),
    UNIQUE KEY uk_pts_voucher_biz_ref (biz_ref),
    KEY idx_pts_voucher_type_time (type_code, occurred_at),
    KEY idx_pts_voucher_store_time (store_no, occurred_at),
    KEY idx_pts_voucher_user_time (user_no, occurred_at),
    KEY idx_pts_voucher_sub (sub_order_no)
) COMMENT='积分凭证：一次业务动作一张，biz_ref 唯一（幂等）';

-- 发放头寸：每支付子单一行（停发也落一行，status=SKIPPED，供「少发了多少」统计）
CREATE TABLE IF NOT EXISTS pts_grant_position (
    id                BIGINT(20)  NOT NULL AUTO_INCREMENT,
    type_code         VARCHAR(32) NOT NULL,
    sub_order_no      VARCHAR(40) NOT NULL,
    order_no          VARCHAR(40) NOT NULL,
    user_no           VARCHAR(64) NOT NULL,
    entity_no         VARCHAR(64) DEFAULT NULL,
    store_no          VARCHAR(64) DEFAULT NULL,
    pay_basis_minor   BIGINT(20)  NOT NULL COMMENT '子单实付快照：退款比例的分母',
    status            VARCHAR(12) NOT NULL COMMENT 'GRANTED / SKIPPED',
    skip_reason       VARCHAR(24) DEFAULT NULL COMMENT 'POOL_EXHAUSTED / PAUSED / OFFLINE / FROZEN / RULE_ZERO / NO_ACTIVE_TYPE',
    skip_points       BIGINT(20)  NOT NULL DEFAULT 0 COMMENT '若不停发本应发多少',
    granted_points    BIGINT(20)  NOT NULL DEFAULT 0,
    activated_points  BIGINT(20)  NOT NULL DEFAULT 0,
    revoked_pending   BIGINT(20)  NOT NULL DEFAULT 0 COMMENT '从待生效收回',
    revoked_available BIGINT(20)  NOT NULL DEFAULT 0 COMMENT '从可用收回（含现金抵扣折算的部分）',
    finalized_at      BIGINT(20)  DEFAULT NULL COMMENT '结算终局时刻（结算域推入）',
    tenant_no VARCHAR(32) DEFAULT NULL, created_at DATETIME DEFAULT NULL, created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME DEFAULT NULL, updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0, deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_pts_grant_sub (type_code, sub_order_no),
    KEY idx_pts_grant_finalized (finalized_at)
) COMMENT='发放头寸：granted = 待生效 + activated + revoked_pending';

CREATE TABLE IF NOT EXISTS pts_hold_position (
    id                  BIGINT(20)  NOT NULL AUTO_INCREMENT,
    type_code           VARCHAR(32) NOT NULL,
    sub_order_no        VARCHAR(40) NOT NULL,
    order_no            VARCHAR(40) NOT NULL,
    user_no             VARCHAR(64) NOT NULL,
    entity_no           VARCHAR(64) NOT NULL COMMENT '收单商家',
    store_no            VARCHAR(64) DEFAULT NULL COMMENT '收单门店',
    pay_basis_minor     BIGINT(20)  NOT NULL COMMENT '子单实付快照',
    held_points         BIGINT(20)  NOT NULL,
    released_points     BIGINT(20)  NOT NULL DEFAULT 0,
    redeemed_points     BIGINT(20)  NOT NULL DEFAULT 0,
    returned_points     BIGINT(20)  NOT NULL DEFAULT 0 COMMENT '兑付后因退款退回给用户的',
    refund_funded_minor BIGINT(20)  NOT NULL DEFAULT 0 COMMENT '结算域告知：补差已回退到账的金额',
    state               VARCHAR(12) NOT NULL DEFAULT 'HELD' COMMENT 'HELD / REDEEMING / REDEEMED / CLOSED',
    redeeming_since     BIGINT(20)  DEFAULT NULL,
    tenant_no VARCHAR(32) DEFAULT NULL, created_at DATETIME DEFAULT NULL, created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME DEFAULT NULL, updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0, deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_pts_hold_sub (type_code, sub_order_no),
    KEY idx_pts_hold_order (order_no),
    KEY idx_pts_hold_state (state, redeeming_since)
) COMMENT='占用头寸：held = 在占 + released + redeemed；returned ≤ redeemed';

CREATE TABLE IF NOT EXISTS pts_pool_request (
    id             BIGINT(20)  NOT NULL AUTO_INCREMENT,
    request_no     VARCHAR(40) NOT NULL,
    kind           VARCHAR(12) NOT NULL COMMENT 'TOPUP / WITHDRAW / ADJUST',
    type_code      VARCHAR(32) NOT NULL,
    amount_minor   BIGINT(20)  NOT NULL DEFAULT 0 COMMENT 'TOPUP / WITHDRAW 金额',
    adjust_user_no VARCHAR(64) DEFAULT NULL,
    adjust_points  BIGINT(20)  NOT NULL DEFAULT 0 COMMENT 'ADJUST 带符号分数',
    evidence_no    VARCHAR(64) DEFAULT NULL COMMENT '打款回单号；TOPUP / WITHDRAW 必填',
    reason         VARCHAR(255) NOT NULL,
    plan_no        VARCHAR(40) DEFAULT NULL COMMENT '由定期补充计划生成时填',
    status         VARCHAR(12) NOT NULL DEFAULT 'SUBMITTED' COMMENT 'SUBMITTED / POSTED / REJECTED',
    submitted_by   VARCHAR(64) NOT NULL,
    submitted_at   BIGINT(20)  NOT NULL,
    decided_by     VARCHAR(64) DEFAULT NULL COMMENT '≠ submitted_by，审批 SQL 里带条件',
    decided_at     BIGINT(20)  DEFAULT NULL,
    decide_remark  VARCHAR(255) DEFAULT NULL,
    voucher_no     VARCHAR(40) DEFAULT NULL,
    tenant_no VARCHAR(32) DEFAULT NULL, created_at DATETIME DEFAULT NULL, created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME DEFAULT NULL, updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0, deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_pts_request_no (request_no),
    KEY idx_pts_request_type (type_code, status)
) COMMENT='补充 / 提取 / 调账单：一人提交、另一人审批后过账';

CREATE TABLE IF NOT EXISTS pts_topup_plan (
    id              BIGINT(20)  NOT NULL AUTO_INCREMENT,
    plan_no         VARCHAR(40) NOT NULL,
    type_code       VARCHAR(32) NOT NULL,
    mode            VARCHAR(20) NOT NULL COMMENT 'FIXED 固定额（分）/ COMMISSION_RATIO 上月佣金 × 万分比',
    value           BIGINT(20)  NOT NULL,
    day_of_month    INT(11)     NOT NULL DEFAULT 1,
    enabled         TINYINT(4)  NOT NULL DEFAULT 1,
    last_run_period VARCHAR(8)  DEFAULT NULL COMMENT 'yyyyMM，生成过就不再生成（幂等）',
    tenant_no VARCHAR(32) DEFAULT NULL, created_at DATETIME DEFAULT NULL, created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME DEFAULT NULL, updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0, deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_pts_plan_no (plan_no),
    UNIQUE KEY uk_pts_plan_type (type_code)
) COMMENT='定期补充计划：只生成待审批的补充单，不自动动钱';

CREATE TABLE IF NOT EXISTS pts_switch (
    id            BIGINT(20)  NOT NULL AUTO_INCREMENT,
    scope_type    VARCHAR(16) NOT NULL COMMENT 'GLOBAL / MARKET / TYPE / SCENE / CLIENT / MERCHANT / STORE / USER',
    scope_id      VARCHAR(64) NOT NULL COMMENT 'GLOBAL 用 *',
    issue_paused  TINYINT(4)  NOT NULL DEFAULT 0,
    redeem_paused TINYINT(4)  NOT NULL DEFAULT 0,
    paused_since  BIGINT(20)  DEFAULT NULL COMMENT '核销暂停起点：恢复时按它顺延到期',
    reason        VARCHAR(255) NOT NULL,
    operator_no   VARCHAR(64) NOT NULL,
    tenant_no VARCHAR(32) DEFAULT NULL, created_at DATETIME DEFAULT NULL, created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME DEFAULT NULL, updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0, deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_pts_switch_scope (scope_type, scope_id)
) COMMENT='积分暂停开关：发放 / 核销分开；没有行 = 放行';

CREATE TABLE IF NOT EXISTS pts_switch_log (
    id            BIGINT(20)  NOT NULL AUTO_INCREMENT,
    scope_type    VARCHAR(16) NOT NULL,
    scope_id      VARCHAR(64) NOT NULL,
    issue_paused  TINYINT(4)  NOT NULL,
    redeem_paused TINYINT(4)  NOT NULL,
    reason        VARCHAR(255) NOT NULL,
    operator_no   VARCHAR(64) NOT NULL,
    occurred_at   BIGINT(20)  NOT NULL,
    tenant_no VARCHAR(32) DEFAULT NULL, created_at DATETIME DEFAULT NULL, created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME DEFAULT NULL, updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0, deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_pts_switch_log_scope (scope_type, scope_id, id)
) COMMENT='暂停开关审计：谁、何时、什么粒度、为什么';

CREATE TABLE IF NOT EXISTS pts_transfer_order (
    id           BIGINT(20)  NOT NULL AUTO_INCREMENT,
    transfer_no  VARCHAR(40) NOT NULL,
    type_code    VARCHAR(32) NOT NULL,
    kind         VARCHAR(20) NOT NULL COMMENT 'TOPUP_IN / WITHDRAW_OUT / REDEEM_COVER（归集兑付回补）/ EXPIRE_INCOME / CASH_OFFSET_IN',
    biz_date     VARCHAR(10) NOT NULL COMMENT '区域本地日 yyyy-MM-dd',
    amount_minor BIGINT(20)  NOT NULL,
    flow_count   INT(11)     NOT NULL,
    executor     VARCHAR(20) NOT NULL DEFAULT 'INTERNAL_BOOKING' COMMENT '本期只有内部记账；将来专户换执行器',
    status       VARCHAR(12) NOT NULL DEFAULT 'CREATED' COMMENT 'CREATED / EXECUTED / RECONCILED / MISMATCH',
    evidence_no  VARCHAR(64) DEFAULT NULL,
    executed_at  BIGINT(20)  DEFAULT NULL,
    tenant_no VARCHAR(32) DEFAULT NULL, created_at DATETIME DEFAULT NULL, created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME DEFAULT NULL, updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0, deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_pts_transfer_no (transfer_no),
    UNIQUE KEY uk_pts_transfer_day (type_code, kind, biz_date)
) COMMENT='划拨单：按日把同类池子流水汇成一笔真钱动作（直连补差不在此，逐单在 stl_split_log）';

CREATE TABLE IF NOT EXISTS pts_stat_daily (
    id           BIGINT(20)  NOT NULL AUTO_INCREMENT,
    biz_date     VARCHAR(10) NOT NULL,
    type_code    VARCHAR(32) NOT NULL,
    scene        VARCHAR(24) NOT NULL,
    voucher_type VARCHAR(16) NOT NULL,
    entity_no    VARCHAR(64) NOT NULL DEFAULT '' COMMENT '空串 = 不属于任何商家（补充、到期等）',
    store_no     VARCHAR(64) NOT NULL DEFAULT '',
    cnt          BIGINT(20)  NOT NULL DEFAULT 0,
    points       BIGINT(20)  NOT NULL DEFAULT 0,
    amount_minor BIGINT(20)  NOT NULL DEFAULT 0,
    tenant_no VARCHAR(32) DEFAULT NULL, created_at DATETIME DEFAULT NULL, created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME DEFAULT NULL, updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0, deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_pts_stat_daily (biz_date, type_code, scene, voucher_type, entity_no, store_no)
) COMMENT='积分日汇总：从 pts_voucher 按水位增量算，可随时重算';

CREATE TABLE IF NOT EXISTS pts_cursor (
    id          BIGINT(20)  NOT NULL AUTO_INCREMENT,
    cursor_name VARCHAR(32) NOT NULL COMMENT 'STAT_ROLLUP / RECON_I2',
    last_id     BIGINT(20)  NOT NULL DEFAULT 0,
    tenant_no VARCHAR(32) DEFAULT NULL, created_at DATETIME DEFAULT NULL, created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME DEFAULT NULL, updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0, deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_pts_cursor_name (cursor_name)
) COMMENT='积分任务水位';
```

#### 条件更新（每条都是一道「写不进去」的闸）

```sql
-- 账户：三格一起改，任一格为负即 0 行。冻结只拦「用户主动」的 A 减少（HOLD），由 #{checkFrozen} 控制
UPDATE pts_account
   SET pending_points = pending_points + #{dP}, available_points = available_points + #{dA},
       held_points = held_points + #{dH}, last_active_at = COALESCE(#{activeAt}, last_active_at),
       expire_at = COALESCE(#{expireAt}, expire_at),
       expire_notified_at = CASE WHEN #{expireAt} IS NULL THEN expire_notified_at ELSE NULL END,
       earned_total = earned_total + #{dEarned}, used_total = used_total + #{dUsed},
       revoked_total = revoked_total + #{dRevoked}, expired_total = expired_total + #{dExpired},
       updated_at = NOW()
 WHERE user_no = #{userNo} AND type_code = #{type} AND deleted = 0
   AND pending_points + #{dP} >= 0 AND available_points + #{dA} >= 0 AND held_points + #{dH} >= 0
   AND (#{checkFrozen} = 0 OR frozen = 0);

-- 到期：在上式基础上再加「A 仍等于读到的值且仍已到期」—— 期间有人下单就跳过，下一轮再判
   AND available_points = #{expectA} AND expire_at <= #{now}

-- 池子：U 扣不动即停发
UPDATE pts_fund_pool
   SET unallocated_minor = unallocated_minor + #{dU}, committed_minor = committed_minor + #{dC},
       receivable_minor = receivable_minor + #{dR}, topup_total_minor = topup_total_minor + #{dTopup}, …
 WHERE type_code = #{type}
   AND unallocated_minor + #{dU} >= 0 AND committed_minor + #{dC} >= 0 AND receivable_minor + #{dR} >= 0;

-- 占用头寸：两阶段兑付
UPDATE pts_hold_position SET state = 'REDEEMING', redeeming_since = #{now}
 WHERE id = #{id} AND state = 'HELD' AND held_points - released_points > 0;          -- beginRedeem
UPDATE pts_hold_position SET released_points = released_points + #{r}
 WHERE id = #{id} AND state = 'HELD' AND released_points + #{r} <= held_points;       -- release
UPDATE pts_hold_position SET state = 'REDEEMED', redeemed_points = held_points - released_points
 WHERE id = #{id} AND state = 'REDEEMING';                                              -- confirmRedeem
UPDATE pts_hold_position SET returned_points = returned_points + #{r}
 WHERE id = #{id} AND state = 'REDEEMED' AND returned_points + #{r} <= redeemed_points;  -- returnBack

-- 发放头寸
UPDATE pts_grant_position SET activated_points = activated_points + #{d}
 WHERE id = #{id} AND activated_points + revoked_pending + #{d} <= granted_points;      -- activate
UPDATE pts_grant_position SET revoked_pending = revoked_pending + #{d}
 WHERE id = #{id} AND activated_points + revoked_pending + #{d} <= granted_points;      -- revokePending
UPDATE pts_grant_position SET revoked_available = revoked_available + #{d}
 WHERE id = #{id} AND revoked_available + #{d} <= activated_points;                     -- revokeAvailable

-- 审批：提交人不能自己批
UPDATE pts_pool_request SET status = 'POSTED', decided_by = #{op}, decided_at = #{now}, voucher_no = #{vno}
 WHERE request_no = #{no} AND status = 'SUBMITTED' AND submitted_by <> #{op};
```

#### 既有表的变化（同一迁移内加列 / 改注释，不删列）

| 表.列 | 变化 |
|---|---|
| `ord_sub_order.points_deduct` / `points_deduct_minor` | 保留，由 `hold` 的分摊结果写入（下单时） |
| `ord_sub_order.points_granted` / `points_fee_minor` | **停写**，注释标废弃 |
| `stl_bill.points_fee_minor` | **停写**（平台出资后不再向商家收费） |
| `stl_bill.subsidy_minor` / `points_cost_minor` | 改为在 `beginRedeem` 时按返回值写入，不再在生成时快照 |
| `ord_after_sale.points_offset_minor` | **开始写**：现金抵扣额 |
| `ord_after_sale.refund_mode` | 新单只允许 `CASH`；`POINTS` 存量保留只读 |
| `mch_entity.points_enabled` / `points_forced` · `sys_industry.points_forced` · `cmt_community.points_enabled` | 停读，注释标废弃 |
| `prd_category_points` | 不变；**第二个区域开通时**再加 `type_code` 维度（本期每区域一种，规则天然归属该区域） |
| `pts_user_account` · `pts_user_ledger` · `stl_points_pool` | 冻结只读，迁移验证后另开迁移删除 |

另需迁移：权限码与功能点种子（`V407__points_v2_perms.sql`，逐行 `INSERT … WHERE NOT EXISTS`，不用 CROSS JOIN）。

### 2.5 接口设计

#### 2.5.1 spi（`shop-base/spi/points`，trade 调 pay）

```java
/** 发放侧。trade 在支付提交后调（AfterCommit）。幂等：GRANT:{subOrderNo} */
public interface PointsEarnPort {
    GrantOutcome grant(GrantCommand cmd);

    record GrantCommand(String market, String userNo, String orderNo, String subOrderNo,
                        String entityNo, String storeNo, String payChannel, String clientScene,
                        long payBasisMinor, long commissionMinor, List<EarnLine> lines) {}
    /** rule 由 trade 经 PointsRulePort 取好传进来，pay 不回查 product */
    record EarnLine(String goodsNo, String categoryNo, int qty, long baseMinor, EarnRule rule, boolean gift) {}
    record GrantOutcome(long points, String status, String skipReason) {}
}

/** 核销侧。 */
public interface PointsRedeemPort {
    /** 结算页试算。与 hold 调同一个 RedeemPolicy —— 两处各算一次就会「说能抵 30、实扣 25」 */
    RedeemQuote quote(RedeemQuery q);
    /** 下单占用。余额不足 / 不可用时返回 none()，不抛 —— 抵扣失败不挡成交。幂等：HOLD:{orderNo} */
    HoldOutcome hold(HoldCommand cmd);
    /** 取消 / 未成交：释放该子单剩余占用。幂等：RELEASE:{subOrderNo}:{reasonRef} */
    void release(String subOrderNo, String reasonRef);

    record RedeemQuery(String market, String userNo, String payMode, String clientScene, List<Target> targets) {}
    record HoldCommand(String market, String userNo, String orderNo, long wantPoints,
                       String payMode, String clientScene, List<Target> targets) {}
    record Target(String subOrderNo, String entityNo, String storeNo, long baseMinor, long payBasisMinor) {}
    record RedeemQuote(String typeCode, long available, long maxPoints, long maxMinor, String denyReason) {}
    record HoldOutcome(long points, long amountMinor, List<Share> shares) {}
    record Share(String subOrderNo, long points, long amountMinor) {}
}

/** 退款。售后在「回退分账」之后、「退现金」之前调。幂等：REFUND:{afterSaleNo} */
public interface PointsRefundPort {
    RefundQuote quote(String subOrderNo, long refundedTotalAfterMinor);
    RefundOutcome apply(String afterSaleNo, String subOrderNo, long refundedTotalAfterMinor);

    /**
     * @param returnedPoints 退回给用户的抵扣积分
     * @param revokedPoints  收回的返分
     * @param cashOffsetMinor 收回不足、要从本次退款现金里扣的金额 —— 退款单必须明示
     */
    record RefundOutcome(long returnedPoints, long revokedPoints, long cashOffsetMinor) {}
    record RefundQuote(long returnedPoints, long revokedPoints, long cashOffsetMinor) {}
}
```

`PointsRulePort#ruleFor(goodsNo, categoryNo)` 签名不变；`FIXED` 的语义改为「每件」（调用方乘件数在 `EarnPolicy` 里做）。

#### 2.5.2 域内（settle 调 points，同模块）

```java
public interface PointsSettlementHook {
    /** 补差前：HELD → REDEEMING，返回此刻真实补差额（0 = 不补差）。结算单 subsidy_minor 以此为准 */
    RedeemTicket beginRedeem(String subOrderNo);
    /** 通道补差成功（直连）或结算单确认（归集）：过账 REDEEM。幂等：REDEEM:{subOrderNo} */
    void confirmRedeem(String subOrderNo, String settleNo);
    /** 补差失败：REDEEMING → HELD */
    void abortRedeem(String subOrderNo, String error);
    /** 发放子单结算终局：记 finalized_at 并转正。幂等：ACTIVATE:{subOrderNo} */
    void onBillFinalized(String subOrderNo);
    /** 补差回退结果：ok 则累加 refund_funded_minor，供退款时判 RETURN 进 C 还是进 R */
    void onSubsidyReturn(String subOrderNo, long minor, boolean ok);

    record RedeemTicket(long points, long amountMinor) {}
}
```

#### 2.5.3 HTTP 端点

**C 端 `/mp/points`**（当前登录人，不接受 userNo）

| 方法 | 路径 | 返回 |
|---|---|---|
| GET | `/mp/points/account?market=` | `{typeCode, name, rate:{points,minor}, available, pending, held, expireAt, expiringSoon}` |
| GET | `/mp/points/records?page&size` | `[{voucherNo, voucherType, title, deltaAvailable, deltaPending, orderNo, at}]`（按凭证聚合，一个动作一行） |
| GET | `/mp/points/quote` | 结算页试算（兼容现有 `/deductible`，旧路径保留一个版本） |

**B 端 `/biz/points`**（作用域取 `BizContext`，只看本店）

| 方法 | 路径 | 返回 |
|---|---|---|
| GET | `/biz/points/summary?period=yyyyMM` | `{issuedPoints, issuedMinor(平台出资), redeemedPoints, subsidyMinor}` |
| GET | `/biz/points/records?period&kind=ISSUE\|REDEEM&page` | 逐单明细 |
| ~~POST~~ | ~~`/biz/points/toggle`~~ | **删除**（商家不再自开积分） |

**运营 `/ops/points`**（每个控制器路径第一段 ≤ 3 种，避开 fat controller 棘轮）

| 控制器 | 方法 | 路径 | 权限码 |
|---|---|---|---|
| `OpsPointTypeController` | GET / POST | `/ops/points/types` | `points:type:read` / `points:type:write` |
| | PUT | `/ops/points/types/{code}`（仅 DRAFT） | `points:type:write` |
| | POST | `/ops/points/types/{code}/activate` · `/retire` | `points:type:write` |
| `OpsPointsPoolController` | GET | `/ops/points/pools/{code}`（U / C / R、可发天数） | `points:pool:read` |
| | GET | `/ops/points/pools/{code}/flows?page` | `points:pool:read` |
| | GET / PUT | `/ops/points/pools/{code}/plan` | `points:pool:read` / `points:pool:submit` |
| | GET / POST | `/ops/points/requests` | `points:pool:read` / `points:pool:submit` |
| | POST | `/ops/points/requests/{no}/approve` · `/reject` | `points:pool:approve` |
| `OpsPointsSwitchController` | GET / PUT | `/ops/points/switches` | `points:switch:read` / `points:switch:write` |
| | GET | `/ops/points/switches/logs` | `points:switch:read` |
| `OpsPointsAccountController` | GET | `/ops/points/accounts?userNo=` · `/accounts/{userNo}/records` | `points:account:read` |
| | POST | `/ops/points/accounts/{userNo}/freeze` · `/unfreeze` | `points:account:write` |
| `OpsPointsStatsController` | GET | `/ops/points/stats/platform?code&from&to&grain=day\|month` | `points:stats:read` |
| | GET | `/ops/points/stats/stores?code&period` · `/stats/stores/{storeNo}/records` | `points:stats:read` |
| | GET | `/ops/points/stats/users?code&sort` | `points:stats:read` |
| | GET | `/ops/points/recon`（最近一轮 I1–I6） | `points:stats:read` |

登记：每个 ops 端点走「/ops 五处登记」（判据见 `scripts/check-ops-contract.mjs`，跨域登记表在 `packages/shared`）；新权限码进权限种子与角色授权（`SUPER_ADMIN`、`FINANCE` 全部；`points:pool:approve` 只给 `FINANCE`）。

#### 2.5.4 界面（三端）

| 端 | 页面 | 动作 |
|---|---|---|
| c-app | 我的积分、结算页积分行、订单详情积分明细 | 改接新接口；结算页去掉「部分可用」的多种类选择（本期每区域一种） |
| b-app | 售后核定面板 | **去掉「积分」退款形式** |
| b-app | 积分页 | 改为本店统计（返出 / 收进），去掉开关 |
| ops-web | 积分种类 · 资金池（含流水与计划）· 补充 / 提取 / 调账审批 · 暂停开关 · 用户积分 · 积分统计 | 新增 6 页，`nav.ts` 加菜单 → 跑菜单生成器落迁移 → `gen-ui-catalog.py` |

#### 2.5.5 配置、错误码、i18n

| 类别 | 项 |
|---|---|
| 配置 | `shop.points.enabled`（`@ConfigurationProperties`，**硬总闸**，生产上线前 false）；`features.points` 改为后端闸门求值结果下发 |
| 错误码 | `POINTS_TYPE_RATE_LOCKED` · `POINTS_TYPE_ACTIVE_EXISTS` · `POINTS_TYPE_NOT_FUNDED` · `POINTS_POOL_INSUFFICIENT` · `POINTS_REQUEST_SELF_APPROVE` · `POINTS_REDEEM_IN_PROGRESS` · `POINTS_ACCOUNT_FROZEN` · `AFTERSALE_REFUND_MODE_UNSUPPORTED`（每个四处登记：枚举 + 三份 properties） |
| i18n（端上） | `points.voucher.{GRANT…EXPIRE}` 流水标题；`points.skip.{reason}`；`points.deny.{reason}`；ops-web 六页文案 |

### 2.6 关键流程

```text
下单
  trade.createOrder ─(落库前)─▶ RedeemPort.hold
      gate(REDEEM) → RedeemPolicy(上限/分摊) → HOLD 凭证：A−y H+y，开占用头寸
      失败/不可用 → none()，订单照常（不抵扣）
支付提交后
  AfterCommit ─▶ EarnPort.grant
      gate(ISSUE) → EarnPolicy → [CommissionCap] → GRANT 凭证：P+x，U−m C+m，开发放头寸
      U 不够 → 头寸 SKIPPED(POOL_EXHAUSTED) + 告警
分账（直连）
  settle.executeSplit ─▶ hook.beginRedeem  (HELD→REDEEMING，返回补差额)
                     ─▶ 通道补差 ─成功─▶ hook.confirmRedeem → REDEEM 凭证：H−y，C−m
                                  └失败─▶ hook.abortRedeem
  分账回执 SPLIT_CONFIRMED ─▶ hook.onBillFinalized → ACTIVATE 凭证：P−x A+x
结算确认（归集）
  settle 确认 ─▶ beginRedeem + confirmRedeem（无通道）─▶ onBillFinalized
退款
  afterSale.doRefund ① settle.reverseSplit（内含补差回退 → hook.onSubsidyReturn）
                     ② RefundPort.apply → RELEASE / RETURN / REVOKE 凭证，返回 cashOffset
                     ③ 退现金 = 退款额 − cashOffset；points_offset_minor 落单
到期（每日，区域时区）
  ExpiryService.expireDue → 每户一张 EXPIRE 凭证：A−a，C−m（出）→ 次日划拨单 EXPIRE_INCOME
```

### 2.7 迁移与切换

1. **先查生产存量**（只读，只查行数与合计）：旧三张表的账户数、`balance + pending_balance` 合计、PENDING 的 USE 合计、`AFTER_SALE_COMP` 行数。
2. 若几乎为空：建新表 → 新链路上线（`shop.points.enabled=false`）→ 运营建种类、补充、生效 → 打开总闸。
3. 若有余额：运营先做一笔 = 存量负债的 `TOPUP` → `OPENING` 迁移脚本逐户过 `ADJUST`（场景 `MIGRATION`）把 A、P 迁入，PENDING 的 USE 迁成占用头寸 → 跑 I1–I4 → 再开总闸。
4. 旧表冻结；一个版本后删旧类、旧表（另开迁移），加 ArchUnit A7。

### 2.8 实施分期（细化计划在确认后由 writing-plans 写进本节）

| 期 | 内容 | 可独立验证的产出 |
|---|---|---|
| 1 | 库表 + model + voucher + posting + store | 凭证平衡单测；真库并发测试（双发放、发放抢池子最后一笔、释放 vs 开始兑付） |
| 2 | 种类 / 池子 / 审批 / 计划 / 开关 / 闸门 + ops 接口 | 运营能建种类、补钱、暂停 |
| 3 | 发放链路（grant / skip / activate / sweeper）接 trade 与 settle | 支付后待生效、终局后可用、池子不足停发 |
| 4 | 核销链路（quote / hold / release / 两阶段兑付） | 补差额以积分域为准 |
| 5 | 退款链路（apply / 现金抵扣 / 待追回）+ 去掉积分退款形式 | 部分退款按比例 |
| 6 | 到期 / 提醒 / 划拨单 | 到期转收入 |
| 7 | 统计 + C / B / ops 页面 + UI 清单 | 三视角 |
| 8 | 对账 I1–I6、外部对账、告警 | 消融后对账能报出差异 |
| 9 | 存量迁移、切换、删旧 | — |

---

## §3 选型

资金模型的选型（商家发分费 / 信用 / 佣金到账进池 / 不设池 / **预存池**）见
[资金方案 §二](./design/积分资金方案-平台预存池.md)；不可逆部分见 [ADR-036](./ADR/ADR-036-积分改为平台出资预存池.md)。

记账方式：

| 方案 | 优点 | 缺点 | 结论 |
|---|---|---|---|
| 三本账各自写 + 事后恒等式（现状演进） | 改动最小 | 每条新路径都可能只改一边；恒等式只能事后说「差了多少」 | ❌ review 的 P0/P1 几乎全属此类 |
| **凭证过账（三本账 + 唯一写入口）** | 平衡在写入时成立；幂等与并发靠库约束 | 多一层凭证表与头寸表 | ✅ |
| 批次（每次发放一个批次，FIFO 消耗） | 每一分可追溯到出资单 | V28 / V30 刚删掉批次；通用积分兑付不需要知道出资来源 | ❌ |

---

## §4 风险

| 风险 | 影响 | 缓解 |
|---|---|---|
| 池子行是热点：每笔支付子单的 `GRANT` 都改同一行 | 高峰期支付后发放排队 | 发放本来就在 `AfterCommit`、不在用户请求路径上；锁顺序把池子放最后；若压测不够，池子 U 拆 N 个分片行（`pts_fund_pool` 加 `shard`），I1 按分片求和 —— 接口不变 |
| 结算终局事件丢失 | 分永远不转正 | `ActivateSweeper` 每小时扫 `finalized_at` 已到而未转正的头寸；I5 核「终局子单都有 ACTIVATE」 |
| 桩网关不产生 `SPLIT_CONFIRMED` | 测试环境分永远不转正 | 场景测试用回执驱动；文档与测试夹具写明 |
| `REDEEMING` 卡住（进程在补差后、确认前崩溃） | 退款被挡（返回处理中） | outbox 重投 `confirmRedeem`；`RedeemingSweeper` 按通道查补差结果后确认或回滚；超 30 分钟告警 |
| 部分回退分账的补差回退额与积分比例不一致 | `RETURN` 判进 C 还是 R 出错 | 补差回退额由结算域按同一累计比例算、经 `onSubsidyReturn` 推入；I5 逐单核 |
| 平台账户内专项被挪用 | 池子账面有钱、实际没钱 | L3 每日校验「平台账户余额 ≥ U + C + 其他待付」，不足即最高级告警 |
| 存量数据迁移 | 余额错迁 | 迁移前先补等额资金；迁后 I1–I4 全绿才开总闸 |
| 运营误操作（错补、错停） | 资金或体验事故 | 补充 / 提取 / 调账双人复核；暂停必填原因 + 审计；种类比例生效后锁定 |

---

## §5 对账三 · 实现 → 需求（测试计划，实现时填「跑过」与「消融」两列）

| AC | 测试 | 类型 | 消融 |
|---|---|---|---|
| AC-P1/P2 | `PointTypeServiceTest#rate_locked_after_activate` · `#second_active_in_market_rejected` | 真库 | 去掉 `active_market` 唯一键 → 红 |
| AC-P3 | `#activate_requires_topup` | 真库 | 去掉校验 → 红 |
| AC-P4 | `PoolServiceTest#self_approve_rejected` · `#withdraw_over_unallocated_rejected` | 真库 | 去掉 `submitted_by <>` → 红 |
| AC-P5 | `PointsTopupPlanJobTest#generates_once_per_period_as_submitted` | 真库 | — |
| AC-P6 | `EarnServiceTest#grant_moves_U_to_C_and_pending` · `#pool_exhausted_skips_whole_order` · **`#two_grants_race_for_last_unit_only_one_wins`** | 真库 + 并发 | 去掉 `unallocated + dU >= 0` → 红 |
| AC-P7 | `#offline_paused_frozen_rule_zero_skipped_with_reason` | 单测 | — |
| AC-P8 | `#activate_only_on_finalized` · `ActivateSweeperTest` | 真库 | — |
| AC-P9/P10 | `DefaultRedeemPolicyTest`（上限、分摊、余数、对齐）· `RedeemServiceTest#offline_denied` · `#non_subsidy_channel_denied` | 单测 | — |
| AC-P11/P12 | **`RedeemRaceTest#release_vs_beginRedeem_exactly_one_wins`** · `#subsidy_uses_live_hold_not_snapshot` | 真库 + 并发 | 去掉 `state='HELD'` 条件 → 红 |
| AC-P13 | `RefundRatioPolicyTest`（多次部分退累计不超、全退精确） | 单测 | — |
| AC-P14 | `PointsRefundServiceTest#shortfall_becomes_cash_offset_never_negative` | 真库 | 去掉 `available + dA >= 0` → 红 |
| AC-P15 | `#return_after_failed_subsidy_return_goes_to_receivable` | 真库 | — |
| AC-P16 | `ExpiryServiceTest#expire_skips_if_available_changed` · `#remind_once` · `#pause_extends_expiry` | 真库 | 去掉 `available = expectA` → 红 |
| AC-P17 | `PointsGateTest`（八个粒度 × 两个动作）· `#pause_does_not_break_in_flight` | 单测 + 真库 | — |
| AC-P18 | `PoolWatermarkTest` | 单测 | — |
| AC-P19 | `PointsStatsServiceTest#rollup_rebuildable_from_vouchers` | 真库 | — |
| AC-P20 | `PointsReconServiceTest#detects_out_of_band_write`（直接改账户一行 → I2 报出该账户） | 真库 | 这条本身就是消融 |
| AC-P21 | `VoucherTest#unbalanced_rejected`（V1 / V2 / V6 各一）· `PostingServiceTest#replay_is_noop` · ArchUnit A1–A6 | 单测 + 架构 | 去掉 `biz_ref` 唯一键 → 重放双写 → 红 |
| AC-P22 | `AfterSaleServiceTest#points_refund_mode_rejected` | 场景 | — |

并发测试照 `RefundLedgerStoreTest` 的写法：两线程同时打同一行，断言恰好一笔成功；去掉条件即红。

---

## §6 对账二 · 设计 → 实现

（实现完成后填：`git show --stat` 与 §2.2 / §2.4 逐行比。）

## §7 确认与完成

| 日期 | 事件 |
|---|---|
| 2026-10-14 | 草稿：资金模型、业务抽象、类设计、依赖、库表、接口 |
