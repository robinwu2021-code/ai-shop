package ai.neargo.shop.food.service.impl;

import ai.neargo.shop.common.BaseEntity;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.dto.CheckCollaborationContracts.ReceivableBatch;
import ai.neargo.shop.food.dto.CounterSettlementContracts.CounterSettlementVO;
import ai.neargo.shop.food.dto.CounterSettlementContracts.ItemVO;
import ai.neargo.shop.food.entity.FnbCheck;
import ai.neargo.shop.food.entity.FoodBusinessOperationRow;
import ai.neargo.shop.food.entity.FnbSettlement;
import ai.neargo.shop.food.entity.FnbSettlementItem;
import ai.neargo.shop.food.mapper.FoodBusinessOperationsMapper;
import ai.neargo.shop.food.mapper.FoodMappers.CheckMapper;
import ai.neargo.shop.food.mapper.FoodMappers.SettlementItemMapper;
import ai.neargo.shop.food.mapper.FoodMappers.SettlementMapper;
import ai.neargo.shop.food.service.CheckBusinessGuardService;
import ai.neargo.shop.food.service.CheckCollaborationService;
import ai.neargo.shop.food.service.CounterSettlementService;
import ai.neargo.shop.spi.trade.IndustryOrderPort;
import ai.neargo.shop.spi.trade.PostpayReceiptPort;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Scope;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 柜台后付整桌结账（TDD-餐饮柜台后付结账 · ADR-059）。
 *
 * <ul>
 *   <li>创建：结算单本身就是冻结 —— 「账单可修改」守卫看到在途（FROZEN / COLLECTING）的结算行就拒绝加菜、退菜，
 *       后厨不受影响。每张待支付的后付核心单一行，应收与版本按门店读核心单得出（与核心确认时同一口径）。</li>
 *   <li>确认：结算单置 COLLECTING → 调核心（按结算单号幂等，一个事务推完整桌）→ SETTLED、台账 CLOSED；
 *       提交之后再单独尝试释放桌台，菜没上齐就保持占用（规则 R4）。核心成功而餐饮收尾失败时，再确认一次会补完。</li>
 *   <li>取消：只要有任何一张核心单已是 PAID 就说明核心已确认，不能取消，应再确认一次完成收尾。</li>
 * </ul>
 */
public class CounterSettlementServiceImpl implements CounterSettlementService {
    static final String KIND = "COUNTER";
    /** 柜台结算单不走顾客 H5 的恢复任务：恢复时间直接填「已结束」。 */
    static final LocalDateTime NO_RECOVERY = LocalDateTime.of(9999, 12, 31, 23, 59, 59);
    static final String RELEASE_REASON = "COUNTER_SETTLED";

    private final SettlementMapper settlements;
    private final SettlementItemMapper items;
    private final CheckMapper checks;
    private final FoodBusinessOperationsMapper operations;
    private final CheckBusinessGuardService guard;
    private final CheckCollaborationService collaboration;
    private final Supplier<IndustryOrderPort> orders;
    private final Supplier<PostpayReceiptPort> receipts;
    private final Clock clock;
    private final TransactionTemplate tx;

    public CounterSettlementServiceImpl(SettlementMapper settlements, SettlementItemMapper items, CheckMapper checks,
            FoodBusinessOperationsMapper operations, CheckBusinessGuardService guard,
            CheckCollaborationService collaboration, Supplier<IndustryOrderPort> orders,
            Supplier<PostpayReceiptPort> receipts, Clock clock, PlatformTransactionManager manager) {
        this.settlements = settlements;
        this.items = items;
        this.checks = checks;
        this.operations = operations;
        this.guard = guard;
        this.collaboration = collaboration;
        this.orders = orders;
        this.receipts = receipts;
        this.clock = clock;
        this.tx = new TransactionTemplate(manager);
    }

    // ---------------------------------------------------------------- 创建

    @Override
    public CounterSettlementVO create(String e, String store, String actor, String checkNo, String requestKey) {
        if (requestKey == null || requestKey.isBlank() || requestKey.length() > 64) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        String requestHash = sha256("COUNTER|" + store + "|" + checkNo);
        FnbSettlement replay = byRequestKey(e, store, requestKey);
        if (replay != null) {
            if (!requestHash.equals(replay.getRequestHash())) {
                throw BizException.of(ErrorCode.CONFLICT);
            }
            return view(replay);
        }
        // 先记下本地账单（版本、绑定、菜行），再读核心应收；核心读放在事务外（可能是远程调用）
        String localBefore = localFingerprint(e, store, checkNo);
        var preview = collaboration.previewReceivable(e, store, checkNo);
        var scope = new Scope("MAIN", e, store);
        var roots = new ArrayList<Root>();
        for (ReceivableBatch batch : preview.batches()) {
            IndustryOrderPort.Result core = orders.get().getForStore(scope, actor, batch.orderNo());
            if (!"POSTPAY".equals(core.orderMode()) || !"WAIT_PAY".equals(core.status())) {
                continue; // 已付的或先付的不在柜台后付结账范围
            }
            long due = PostpayReceiptPort.dueMinor(core);
            if (batch.effectiveMinor() != due) {
                throw BizException.of(ErrorCode.COUNTER_SETTLEMENT_SPLIT_LINES); // 这张单有菜在别的台账上
            }
            roots.add(new Root(batch.orderNo(), batch.subOrderNo(), core.currency(), due,
                    PostpayReceiptPort.coreVersion(core)));
        }
        if (roots.isEmpty() || roots.stream().mapToLong(Root::due).sum() <= 0) {
            throw BizException.of(ErrorCode.BAD_REQUEST); // 没有待收的后付单
        }
        String currency = roots.getFirst().currency();
        if (roots.stream().anyMatch(r -> !currency.equals(r.currency()))) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        try {
            return tx.execute(t -> {
                guard.assertMutable(e, store, checkNo); // OPEN、未冻结、没有在途结算
                if (operations.checkCollaborationCountOrderBatch(e, store, checkNo) > 0
                        || operations.checkBusinessGuardPendingReturns(e, store, checkNo) > 0) {
                    throw BizException.of(ErrorCode.COUNTER_SETTLEMENT_NOT_READY);
                }
                // 账单已锁（assertMutable 对 fnb_check 加了行锁）：本地账单与读应收前一致才冻结。
                // 不调 collaboration.sourceHash —— 它要读核心，规定不能在持有餐饮锁时调用。
                if (!localFingerprint(e, store, checkNo).equals(localBefore)) {
                    throw BizException.of(ErrorCode.POSTPAY_RECEIPT_ORDER_CHANGED);
                }
                var settlement = new FnbSettlement();
                stamp(settlement, e, actor);
                settlement.setSettlementNo(BizKey.next("FSET"));
                settlement.setEntityNo(e);
                settlement.setStoreNo(store);
                settlement.setCurrency(currency);
                settlement.setDueMinor(roots.stream().mapToLong(Root::due).sum());
                settlement.setStatus("FROZEN");
                settlement.setScopeHash(preview.sourceHash());
                settlement.setQuoteRef("CHECK:" + checkNo);
                settlement.setExpiresAt(NO_RECOVERY);
                settlement.setNextRecoveryAt(NO_RECOVERY);
                settlement.setRequestKey(requestKey);
                settlement.setRequestHash(requestHash);
                settlement.setKind(KIND);
                settlements.insert(settlement);
                for (Root root : roots) {
                    String scopeKey = root.order() + ":FULL";
                    if (items.activeFullScopeCount(e, store, root.order(), scopeKey) != 0) {
                        throw BizException.of(ErrorCode.CONFLICT);
                    }
                    var item = new FnbSettlementItem();
                    stamp(item, e, actor);
                    item.setEntityNo(e);
                    item.setScopeNo(BizKey.next("FSCP"));
                    item.setSettlementNo(settlement.getSettlementNo());
                    item.setCheckNo(checkNo);
                    item.setPaymentRootNo(root.order());
                    item.setStoreOrderNo(root.subOrder());
                    item.setDueMinor(root.due());
                    item.setCoreVersion(root.version());
                    item.setScopeKey(scopeKey);
                    items.insert(item);
                }
                return view(settlement);
            });
        } catch (DuplicateKeyException sameRequestConcurrently) {
            FnbSettlement winner = byRequestKey(e, store, requestKey);
            if (winner == null || !requestHash.equals(winner.getRequestHash())) {
                throw BizException.of(ErrorCode.CONFLICT);
            }
            return view(winner);
        }
    }

    private record Root(String order, String subOrder, String currency, long due, String version) {
    }

    // ---------------------------------------------------------------- 确认收齐

    @Override
    public CounterSettlementVO finalize(String e, String store, String actor, String settlementNo,
            List<String> offlineRecordNos) {
        if (offlineRecordNos == null || offlineRecordNos.isEmpty() || offlineRecordNos.size() > 20) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        FnbSettlement settlement = tx.execute(t -> {
            FnbSettlement s = require(e, store, settlementNo);
            if ("CANCELLED".equals(s.getStatus())) {
                throw BizException.of(ErrorCode.CONFLICT);
            }
            if ("FROZEN".equals(s.getStatus())) {
                s.setStatus("COLLECTING");
                touch(s, actor);
                if (settlements.updateById(s) != 1) {
                    throw BizException.of(ErrorCode.CONFLICT);
                }
            }
            return s;
        });
        List<FnbSettlementItem> rows = itemsOf(e, settlementNo);
        String checkNo = rows.getFirst().getCheckNo();
        // 核心按结算单号幂等：已确认过就原样返回（用于收尾失败后的重试）
        var receipt = receipts.get().confirm(new PostpayReceiptPort.Confirm("MAIN", e, store, settlementNo,
                settlement.getCurrency(),
                rows.stream().map(i -> new PostpayReceiptPort.Root(i.getPaymentRootNo(), i.getDueMinor(),
                        i.getCoreVersion())).toList(),
                offlineRecordNos, actor, settlementNo));
        tx.executeWithoutResult(t -> {
            FnbSettlement s = require(e, store, settlementNo);
            if (!"SETTLED".equals(s.getStatus())) {
                s.setStatus("SETTLED");
                s.setCoreCashierRef(receipt.receiptNo());
                touch(s, actor);
                if (settlements.updateById(s) != 1) {
                    throw BizException.of(ErrorCode.CONFLICT);
                }
            }
            operations.counterSettlementCloseCheck(actor, e, store, checkNo); // 已关过则 0 行，无妨
        });
        boolean released = tryRelease(e, store, actor, checkNo);
        FnbSettlement settled = require(e, store, settlementNo);
        return view(settled, receipt.receivedMinor(), receipt.changeMinor(), released);
    }

    /** 菜已上齐（或作废）就释放桌台；否则保持占用，等上齐后服务员手动释放（规则 R4）。 */
    private boolean tryRelease(String e, String store, String actor, String checkNo) {
        FnbCheck check = check(e, store, checkNo);
        if (check == null || "RELEASED".equals(check.getServiceState())) {
            return check != null;
        }
        try {
            collaboration.releaseService(e, store, actor, checkNo, check.getVersion(), RELEASE_REASON,
                    "FSET-REL:" + checkNo + ":" + check.getVersion());
            return true;
        } catch (BizException notYet) {
            return false;
        }
    }

    // ---------------------------------------------------------------- 取消

    @Override
    public CounterSettlementVO cancel(String e, String store, String actor, String settlementNo) {
        FnbSettlement current = require(e, store, settlementNo);
        if ("CANCELLED".equals(current.getStatus())) {
            return view(current);
        }
        if ("SETTLED".equals(current.getStatus())) {
            throw BizException.of(ErrorCode.COUNTER_SETTLEMENT_CONFIRMED);
        }
        // COLLECTING 时核心可能已经确认：任何一张核心单已是 PAID 就不能取消
        var scope = new Scope("MAIN", e, store);
        for (FnbSettlementItem item : itemsOf(e, settlementNo)) {
            var core = orders.get().getForStore(scope, actor, item.getPaymentRootNo());
            if (!"WAIT_PAY".equals(core.status())) {
                throw BizException.of(ErrorCode.COUNTER_SETTLEMENT_CONFIRMED);
            }
        }
        return tx.execute(t -> {
            FnbSettlement s = require(e, store, settlementNo);
            if (!"FROZEN".equals(s.getStatus()) && !"COLLECTING".equals(s.getStatus())) {
                throw BizException.of(ErrorCode.CONFLICT);
            }
            s.setStatus("CANCELLED");
            touch(s, actor);
            if (settlements.updateById(s) != 1) {
                throw BizException.of(ErrorCode.CONFLICT);
            }
            return view(s);
        });
    }

    @Override
    public CounterSettlementVO get(String e, String store, String settlementNo) {
        return view(require(e, store, settlementNo));
    }

    // ---------------------------------------------------------------- 辅助

    /** 本地账单指纹：台账版本 + 当前绑定的核心单 + 归属这张台账的菜行（含数量与版本）。 */
    private String localFingerprint(String e, String store, String checkNo) {
        var version = operations.checkCollaborationSelectCheck(e, store, checkNo).stream()
                .map(FoodBusinessOperationRow::snapshot).toList();
        var bindings = operations.currentCoreBindings(e, store, checkNo).stream()
                .map(FoodBusinessOperationRow::snapshot).toList();
        var lines = operations.checkCollaborationSelectOrderLine(e, store, checkNo).stream()
                .map(FoodBusinessOperationRow::snapshot).toList();
        return sha256(version + "|" + bindings + "|" + lines);
    }

    private FnbSettlement require(String e, String store, String settlementNo) {
        FnbSettlement s = settlements.selectOne(Wrappers.<FnbSettlement>lambdaQuery()
                .eq(FnbSettlement::getTenantNo, "MAIN").eq(FnbSettlement::getEntityNo, e)
                .eq(FnbSettlement::getStoreNo, store).eq(FnbSettlement::getSettlementNo, settlementNo)
                .eq(FnbSettlement::getKind, KIND).eq(FnbSettlement::getDeleted, 0));
        if (s == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        return s;
    }

    private FnbSettlement byRequestKey(String e, String store, String key) {
        return settlements.selectOne(Wrappers.<FnbSettlement>lambdaQuery()
                .eq(FnbSettlement::getTenantNo, "MAIN").eq(FnbSettlement::getEntityNo, e)
                .eq(FnbSettlement::getStoreNo, store).eq(FnbSettlement::getRequestKey, key)
                .eq(FnbSettlement::getKind, KIND).eq(FnbSettlement::getDeleted, 0));
    }

    private List<FnbSettlementItem> itemsOf(String e, String settlementNo) {
        return items.selectList(Wrappers.<FnbSettlementItem>lambdaQuery()
                .eq(FnbSettlementItem::getEntityNo, e).eq(FnbSettlementItem::getSettlementNo, settlementNo)
                .eq(FnbSettlementItem::getDeleted, 0).orderByAsc(FnbSettlementItem::getId));
    }

    private FnbCheck check(String e, String store, String checkNo) {
        return checks.selectOne(Wrappers.<FnbCheck>lambdaQuery().eq(FnbCheck::getEntityNo, e)
                .eq(FnbCheck::getStoreNo, store).eq(FnbCheck::getCheckNo, checkNo).eq(FnbCheck::getDeleted, 0));
    }

    private CounterSettlementVO view(FnbSettlement s) {
        return view(s, null, null, false);
    }

    private CounterSettlementVO view(FnbSettlement s, Long received, Long change, boolean released) {
        var rows = itemsOf(s.getEntityNo(), s.getSettlementNo());
        String checkNo = rows.isEmpty() ? null : rows.getFirst().getCheckNo();
        FnbCheck check = checkNo == null ? null : check(s.getEntityNo(), s.getStoreNo(), checkNo);
        boolean tableReleased = released || check != null && "RELEASED".equals(check.getServiceState());
        return new CounterSettlementVO(s.getSettlementNo(), checkNo, s.getStatus(), s.getCurrency(),
                s.getDueMinor(), received, change, s.getCoreCashierRef(),
                rows.stream().map(i -> new ItemVO(i.getPaymentRootNo(), i.getStoreOrderNo(), i.getDueMinor())).toList(),
                check == null ? null : check.getStatus(), tableReleased);
    }

    private void stamp(BaseEntity row, String e, String actor) {
        LocalDateTime now = now();
        row.setTenantNo("MAIN");
        row.setVersion(0L);
        row.setDeleted(0);
        row.setCreatedBy(actor);
        row.setUpdatedBy(actor);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
    }

    private void touch(BaseEntity row, String actor) {
        row.setUpdatedBy(actor);
        row.setUpdatedAt(now());
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
