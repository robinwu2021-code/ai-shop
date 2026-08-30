package ai.neargo.shop.merchant.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.merchant.entity.MchDebt;
import ai.neargo.shop.merchant.entity.MchDebtTxn;
import ai.neargo.shop.merchant.mapper.MerchantMappers.DebtMapper;
import ai.neargo.shop.merchant.mapper.MerchantMappers.DebtTxnMapper;
import ai.neargo.shop.merchant.service.DebtService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class DebtServiceImpl implements DebtService {

    private static final Logger log = LoggerFactory.getLogger(DebtServiceImpl.class);

    private final DebtMapper debtMapper;
    private final DebtTxnMapper txnMapper;

    public DebtServiceImpl(DebtMapper debtMapper, DebtTxnMapper txnMapper) {
        this.debtMapper = debtMapper;
        this.txnMapper = txnMapper;
    }

    @Override
    @Transactional
    public long incur(String entityNo, long amountMinor, String sourceType,
                      String sourceNo, String reason) {
        if (amountMinor <= 0) {
            // 0 或负数记进来只会污染流水：欠款的方向是单一的
            return balanceOf(entityNo);
        }
        if (sourceNo == null || sourceNo.isBlank()) {
            throw new IllegalArgumentException("欠款必须指得出源头，sourceNo 不能为空");
        }
        /*
         * 幂等靠**源单号**，不靠「处理过没有」的标记。
         * 售后事件会重投，而重投一次就让商家凭空多欠一笔。
         * 先查再插 + 唯一键双保险：并发重投时两条路径都可能撞上。
         */
        boolean exists = DataScopeContext.executeWithoutScope(() ->
                txnMapper.selectCount(Wrappers.<MchDebtTxn>lambdaQuery()
                        .eq(MchDebtTxn::getEntityNo, entityNo)
                        .eq(MchDebtTxn::getSourceType, sourceType)
                        .eq(MchDebtTxn::getSourceNo, sourceNo))) > 0;
        if (exists) {
            log.info("[debt] 源单 {} 的欠款已记过，跳过", sourceNo);
            return balanceOf(entityNo);
        }
        MchDebt account = accountOf(entityNo, true);
        long after = nz(account.getBalanceMinor()) + amountMinor;
        account.setBalanceMinor(after);
        account.setTotalIncurredMinor(nz(account.getTotalIncurredMinor()) + amountMinor);
        account.setLastIncurredAt(System.currentTimeMillis());
        DataScopeContext.executeWithoutScope(() -> debtMapper.updateById(account));
        writeTxn(entityNo, MchDebtTxn.INCUR, amountMinor, after, sourceType, sourceNo, null, reason);
        log.warn("[debt] {} 新增欠款 {} 分（源 {}），余额 {}", entityNo, amountMinor, sourceNo, after);
        return after;
    }

    @Override
    @Transactional
    public long offset(String entityNo, long payableMinor, String batchNo) {
        if (payableMinor <= 0) {
            return 0L;
        }
        MchDebt account = accountOf(entityNo, false);
        long owed = account == null ? 0L : nz(account.getBalanceMinor());
        if (owed <= 0) {
            return 0L;
        }
        /*
         * 抵扣不能超过这一批能放的钱：超了就变成「从商家的下一批里预扣」，
         * 而那一批还没成交 —— 账上会出现一笔没有对应货款的扣款。
         */
        long take = Math.min(owed, payableMinor);
        long after = owed - take;
        account.setBalanceMinor(after);
        account.setTotalRepaidMinor(nz(account.getTotalRepaidMinor()) + take);
        DataScopeContext.executeWithoutScope(() -> debtMapper.updateById(account));
        // 有符号：偿还为负
        writeTxn(entityNo, MchDebtTxn.OFFSET, -take, after, null, null, batchNo,
                "从批次 " + batchNo + " 的货款中抵扣");
        log.info("[debt] {} 抵扣 {} 分（批次 {}），余额 {}", entityNo, take, batchNo, after);
        return take;
    }

    @Override
    public long balanceOf(String entityNo) {
        MchDebt a = accountOf(entityNo, false);
        return a == null ? 0L : nz(a.getBalanceMinor());
    }

    @Override
    public List<TxnVO> txns(String entityNo) {
        return DataScopeContext.executeWithoutScope(() ->
                        txnMapper.selectList(Wrappers.<MchDebtTxn>lambdaQuery()
                                .eq(MchDebtTxn::getEntityNo, entityNo)
                                .orderByDesc(MchDebtTxn::getId)))
                .stream()
                .map(t -> new TxnVO(t.getTxnNo(), t.getTxnType(), nz(t.getAmountMinor()),
                        nz(t.getBalanceAfterMinor()), t.getSourceType(), t.getSourceNo(),
                        t.getBatchNo(), t.getReason(),
                        t.getCreatedAt() == null ? 0L
                                : t.getCreatedAt().atZone(java.time.ZoneId.systemDefault())
                                        .toInstant().toEpochMilli()))
                .toList();
    }

    /** @param create 没有账户时建一个 —— 只在真要记欠款时建，查询不建空账户 */
    private MchDebt accountOf(String entityNo, boolean create) {
        MchDebt a = DataScopeContext.executeWithoutScope(() ->
                debtMapper.selectOne(Wrappers.<MchDebt>lambdaQuery()
                        .eq(MchDebt::getEntityNo, entityNo).last("LIMIT 1")));
        if (a != null || !create) {
            return a;
        }
        MchDebt fresh = new MchDebt();
        fresh.setEntityNo(entityNo);
        fresh.setBalanceMinor(0L);
        fresh.setTotalIncurredMinor(0L);
        fresh.setTotalRepaidMinor(0L);
        DataScopeContext.executeWithoutScope(() -> debtMapper.insert(fresh));
        return fresh;
    }

    private void writeTxn(String entityNo, String type, long amount, long after,
                          String sourceType, String sourceNo, String batchNo, String reason) {
        MchDebtTxn t = new MchDebtTxn();
        t.setTxnNo(BizKey.next(BizKey.DEBT_TXN));
        t.setEntityNo(entityNo);
        t.setTxnType(type);
        t.setAmountMinor(amount);
        t.setBalanceAfterMinor(after);
        t.setSourceType(sourceType);
        t.setSourceNo(sourceNo);
        t.setBatchNo(batchNo);
        t.setReason(reason);
        DataScopeContext.executeWithoutScope(() -> txnMapper.insert(t));
    }

    private static long nz(Long v) {
        return v == null ? 0L : v;
    }
}
