package ai.neargo.shop.merchant.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.merchant.entity.MchAdmissionPolicy;
import ai.neargo.shop.merchant.entity.MchDeposit;
import ai.neargo.shop.merchant.entity.MchDepositTxn;
import ai.neargo.shop.merchant.entity.MchEntity;
import ai.neargo.shop.merchant.entity.MchPaymentMerchant;
import ai.neargo.shop.merchant.mapper.MerchantMappers.AdmissionPolicyMapper;
import ai.neargo.shop.merchant.mapper.MerchantMappers.DepositMapper;
import ai.neargo.shop.merchant.mapper.MerchantMappers.DepositTxnMapper;
import ai.neargo.shop.merchant.mapper.MerchantMappers.MchEntityMapper;
import ai.neargo.shop.merchant.mapper.MerchantMappers.MchPaymentMapper;
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
    private final MchPaymentMapper paymentMapper;

    public AdmissionServiceImpl(AdmissionPolicyMapper policyMapper, DepositMapper depositMapper,
                                DepositTxnMapper txnMapper, MchEntityMapper merchantMapper,
                                MchPaymentMapper paymentMapper) {
        this.paymentMapper = paymentMapper;
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
        /*
         * 备注也要能改。**此前这一个字段被漏掉了**：其余六个都 patch，唯独它不。
         * 而它恰恰是被回溯质问时最有用的那一栏 ——「那单当时为什么放行」，
         * 答案是数字旁边这句话，不是数字本身。
         * 漏掉的坏法很安静：运营填了理由、点保存、看到成功，回头一看还是旧的。
         */
        if (patch.getRemark() != null) {
            row.setRemark(patch.getRemark());
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
    public void recordTxn(String merchantNo, String txnType, long amountMinor, String reason,
                          String operator, String requestNo) {
        if (requestNo == null || requestNo.isBlank()) {
            /*
             * **漏传当场 400，不静默放行。**
             *
             * 放行的话这个接口在「端上忘了传」的情况下与没接幂等一模一样，
             * 而那正是 Idempotency-Key 头那套东西的毛病：
             * 没带 key 就直接执行，于是「接没接上」在服务端看不出来。
             */
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        if (DataScopeContext.executeWithoutScope(() -> txnMapper.selectCount(
                Wrappers.<MchDepositTxn>lambdaQuery()
                        .eq(MchDepositTxn::getMerchantNo, merchantNo)
                        .eq(MchDepositTxn::getRequestNo, requestNo))) > 0) {
            // 这次操作已经做过。**返回而不是报错** —— 用户看到的是「点了两下」，
            // 报错会让他以为没成功，然后换个号再点一次
            return;
        }
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
        txn.setRequestNo(requestNo);
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

    @Override
    @Transactional
    public void setPayQuotaLimit(String merchantNo, String storeNo, long quotaLimitMinor,
                                 String operator) {
        if (quotaLimitMinor < 0) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        MchPaymentMerchant pm = paymentMapper.selectOne(Wrappers.<MchPaymentMerchant>lambdaQuery()
                .eq(MchPaymentMerchant::getEntityNo, merchantNo)
                .eq(MchPaymentMerchant::getStoreNo,
                        storeNo == null || storeNo.isBlank()
                                ? MchPaymentMerchant.ENTITY_LEVEL : storeNo)
                .last("LIMIT 1"));
        if (pm == null) {
            // 没有收款记录就没有额度可设 —— 静默建一条会造出一个没进过件的收款号
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        // 只设上限，不动已用量：用量是支付累加出来的事实，
        // 让运营能改它等于让人可以把账做平
        pm.setQuotaLimitMinor(quotaLimitMinor);
        pm.setUpdatedBy(operator);
        paymentMapper.updateById(pm);
    }

    @Override
    public List<PayQuotaVO> payQuotas(String merchantNo) {
        return paymentMapper.selectList(Wrappers.<MchPaymentMerchant>lambdaQuery()
                        .eq(MchPaymentMerchant::getEntityNo, merchantNo)
                        .orderByAsc(MchPaymentMerchant::getStoreNo))
                .stream()
                .map(pm -> new PayQuotaVO(
                        pm.getStoreNo() == null ? MchPaymentMerchant.ENTITY_LEVEL : pm.getStoreNo(),
                        pm.getPayChannel(), pm.getApplyStatus(),
                        orZero(pm.getQuotaLimitMinor()), orZero(pm.getQuotaUsedMinor())))
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
