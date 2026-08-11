package ai.neargo.shop.merchant.service.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.merchant.entity.MchAdmissionPolicy;
import ai.neargo.shop.merchant.entity.MchDeposit;
import ai.neargo.shop.merchant.entity.MchDepositTxn;
import ai.neargo.shop.merchant.entity.MchEntity;
import ai.neargo.shop.merchant.mapper.MerchantMappers.AdmissionPolicyMapper;
import ai.neargo.shop.merchant.mapper.MerchantMappers.DepositMapper;
import ai.neargo.shop.merchant.mapper.MerchantMappers.DepositTxnMapper;
import ai.neargo.shop.merchant.mapper.MerchantMappers.MchEntityMapper;
import ai.neargo.shop.merchant.service.AdmissionService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdmissionServiceImpl implements AdmissionService {

    private final AdmissionPolicyMapper policyMapper;
    private final DepositMapper depositMapper;
    private final DepositTxnMapper txnMapper;
    private final MchEntityMapper merchantMapper;

    public AdmissionServiceImpl(AdmissionPolicyMapper policyMapper, DepositMapper depositMapper,
                                DepositTxnMapper txnMapper, MchEntityMapper merchantMapper) {
        this.policyMapper = policyMapper;
        this.depositMapper = depositMapper;
        this.txnMapper = txnMapper;
        this.merchantMapper = merchantMapper;
    }

    @Override
    public List<MchAdmissionPolicy> policies() {
        return policyMapper.selectList(Wrappers.<MchAdmissionPolicy>lambdaQuery()
                .orderByAsc(MchAdmissionPolicy::getLegalForm));
    }

    @Override
    @Transactional
    public void updatePolicy(String legalForm, MchAdmissionPolicy patch, String operator) {
        MchAdmissionPolicy row = policyMapper.selectOne(Wrappers.<MchAdmissionPolicy>lambdaQuery()
                .eq(MchAdmissionPolicy::getLegalForm, legalForm).last("LIMIT 1"));
        if (row == null) {
            // S 轴锁定为三档，凭空多出一档只可能是笔误 —— 静默新建会让笔误变成一条永不生效的策略
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        if (patch.getRequiredDepositMinor() != null) {
            row.setRequiredDepositMinor(patch.getRequiredDepositMinor());
        }
        if (patch.getSingleOrderLimitMinor() != null) {
            row.setSingleOrderLimitMinor(patch.getSingleOrderLimitMinor());
        }
        if (patch.getDailyAmountLimitMinor() != null) {
            row.setDailyAmountLimitMinor(patch.getDailyAmountLimitMinor());
        }
        if (patch.getBanQualifiedCategory() != null) {
            row.setBanQualifiedCategory(patch.getBanQualifiedCategory());
        }
        if (patch.getBannedCategoryCodes() != null) {
            row.setBannedCategoryCodes(patch.getBannedCategoryCodes());
        }
        if (patch.getEnabled() != null) {
            row.setEnabled(patch.getEnabled());
        }
        row.setUpdatedBy(operator);
        policyMapper.updateById(row);
    }

    @Override
    public DepositVO deposit(String merchantNo) {
        MchDeposit d = accountOf(merchantNo).orElse(null);
        long paid = d == null ? 0L : orZero(d.getPaidMinor());
        long frozen = d == null ? 0L : orZero(d.getFrozenMinor());
        long available = paid - frozen;

        MchAdmissionPolicy p = policyOfMerchant(merchantNo).orElse(null);
        long required = p == null ? 0L : orZero(p.getRequiredDepositMinor());
        long single = p == null ? 0L : orZero(p.getSingleOrderLimitMinor());
        long daily = p == null ? 0L : orZero(p.getDailyAmountLimitMinor());

        return new DepositVO(merchantNo, paid, frozen, available, required,
                available >= required, single, daily);
    }

    @Override
    @Transactional
    public void recordTxn(String merchantNo, String txnType, long amountMinor, String reason, String operator) {
        MchDeposit d = accountOf(merchantNo).orElseGet(() -> {
            MchDeposit fresh = new MchDeposit();
            fresh.setMerchantNo(merchantNo);
            fresh.setPaidMinor(0L);
            fresh.setFrozenMinor(0L);
            depositMapper.insert(fresh);
            return fresh;
        });

        if (MchDepositTxn.FREEZE.equals(txnType) || MchDepositTxn.UNFREEZE.equals(txnType)) {
            /*
             * 冻结/解冻动的是 frozen 而不是 paid：钱还在账上，只是不能用来撑准入。
             * 若把冻结记成 paid 减少，理赔一旦不成立就没法还原「本来缴了多少」。
             */
            long frozen = orZero(d.getFrozenMinor())
                    + (MchDepositTxn.FREEZE.equals(txnType) ? Math.abs(amountMinor) : -Math.abs(amountMinor));
            if (frozen < 0) {
                frozen = 0;
            }
            d.setFrozenMinor(frozen);
        } else {
            /*
             * 符号方向必须与类型一致。
             *
             * 缴纳只能是正、退还与扣划只能是负 —— 不校验的后果不是报错而是**账反了**：
             * 运营选「退还 2000」，端上若发成正数，实缴从 2000 涨到 4000，
             * 流水上还写着「退还」。两侧都不报错，只有对账时才会发现。
             * 这一条已经真实发生过一次（ops-web 只对 DEDUCT 取了负）。
             */
            boolean shouldBeNegative = MchDepositTxn.REFUND.equals(txnType)
                    || MchDepositTxn.DEDUCT.equals(txnType);
            if (amountMinor == 0 || (shouldBeNegative ? amountMinor > 0 : amountMinor < 0)) {
                throw BizException.of(ErrorCode.BAD_REQUEST);
            }
            long paid = orZero(d.getPaidMinor()) + amountMinor;
            if (paid < 0) {
                // 保证金扣成负数意味着平台已经垫付，那是另一笔账，不该混在这张表里
                throw BizException.of(ErrorCode.BAD_REQUEST);
            }
            d.setPaidMinor(paid);
        }
        d.setUpdatedBy(operator);
        depositMapper.updateById(d);

        MchDepositTxn txn = new MchDepositTxn();
        txn.setTxnNo(BizKey.next(BizKey.DEPOSIT_TXN));
        txn.setMerchantNo(merchantNo);
        txn.setTxnType(txnType);
        txn.setAmountMinor(amountMinor);
        txn.setBalanceAfterMinor(orZero(d.getPaidMinor()));
        txn.setReason(reason);
        txn.setOperator(operator);
        txnMapper.insert(txn);
    }

    @Override
    public List<TxnVO> txns(String merchantNo) {
        return txnMapper.selectList(Wrappers.<MchDepositTxn>lambdaQuery()
                        .eq(MchDepositTxn::getMerchantNo, merchantNo)
                        .orderByDesc(MchDepositTxn::getId))
                .stream()
                .map(t -> new TxnVO(t.getTxnNo(), t.getTxnType(), orZero(t.getAmountMinor()),
                        orZero(t.getBalanceAfterMinor()), t.getReason(), t.getOperator(),
                        t.getCreatedAt() == null ? null : t.getCreatedAt().toString()))
                .toList();
    }

    private Optional<MchDeposit> accountOf(String merchantNo) {
        return Optional.ofNullable(depositMapper.selectOne(Wrappers.<MchDeposit>lambdaQuery()
                .eq(MchDeposit::getMerchantNo, merchantNo).last("LIMIT 1")));
    }

    private Optional<MchAdmissionPolicy> policyOfMerchant(String merchantNo) {
        MchEntity m = merchantMapper.selectOne(Wrappers.<MchEntity>lambdaQuery()
                .eq(MchEntity::getEntityNo, merchantNo).last("LIMIT 1"));
        if (m == null || m.getLegalForm() == null || m.getLegalForm().isBlank()) {
            return Optional.empty();
        }
        MchAdmissionPolicy p = policyMapper.selectOne(Wrappers.<MchAdmissionPolicy>lambdaQuery()
                .eq(MchAdmissionPolicy::getLegalForm, m.getLegalForm()).last("LIMIT 1"));
        return p != null && p.active() ? Optional.of(p) : Optional.empty();
    }

    private static long orZero(Long v) {
        return v == null ? 0L : v;
    }
}
