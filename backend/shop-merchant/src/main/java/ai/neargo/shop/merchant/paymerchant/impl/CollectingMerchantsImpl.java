package ai.neargo.shop.merchant.paymerchant.impl;

import ai.neargo.shop.merchant.paymerchant.CollectingMerchants;
import ai.neargo.shop.merchant.paymerchant.entity.MchPaymentMerchant;
import ai.neargo.shop.merchant.paymerchant.mapper.PayMerchantMappers.MchPaymentMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** 读写商户域这一侧的旧表 {@code mch_payment_merchant}。每个方法对应原来商户核心里的一处查询，逐字保留。 */
@Service
public class CollectingMerchantsImpl implements CollectingMerchants {

    private final MchPaymentMapper payments;

    public CollectingMerchantsImpl(MchPaymentMapper payments) {
        this.payments = payments;
    }

    @Override
    public List<MchPaymentMerchant> all(String entityNo) {
        return payments.selectList(Wrappers.<MchPaymentMerchant>lambdaQuery()
                .eq(MchPaymentMerchant::getEntityNo, entityNo));
    }

    @Override
    public List<MchPaymentMerchant> allByStore(String entityNo) {
        return payments.selectList(Wrappers.<MchPaymentMerchant>lambdaQuery()
                .eq(MchPaymentMerchant::getEntityNo, entityNo).orderByAsc(MchPaymentMerchant::getStoreNo));
    }

    @Override
    public List<MchPaymentMerchant> active(String entityNo) {
        return payments.selectList(Wrappers.<MchPaymentMerchant>lambdaQuery()
                .eq(MchPaymentMerchant::getEntityNo, entityNo)
                .eq(MchPaymentMerchant::getApplyStatus, MchPaymentMerchant.ACTIVE));
    }

    @Override
    public boolean hasActive(String entityNo) {
        return payments.selectCount(Wrappers.<MchPaymentMerchant>lambdaQuery()
                .eq(MchPaymentMerchant::getEntityNo, entityNo)
                .eq(MchPaymentMerchant::getApplyStatus, MchPaymentMerchant.ACTIVE)) > 0;
    }

    @Override
    public boolean exists(String entityNo) {
        return payments.exists(Wrappers.<MchPaymentMerchant>lambdaQuery().eq(MchPaymentMerchant::getEntityNo, entityNo));
    }

    @Override
    public List<MchPaymentMerchant> byPayMerchantNo(String entityNo, String payMerchantNo) {
        return payments.selectList(Wrappers.<MchPaymentMerchant>lambdaQuery()
                .eq(MchPaymentMerchant::getEntityNo, entityNo)
                .eq(MchPaymentMerchant::getPayMerchantNo, payMerchantNo));
    }

    @Override
    public Optional<MchPaymentMerchant> firstByPayMerchantNo(String entityNo, String payMerchantNo) {
        return Optional.ofNullable(payments.selectOne(Wrappers.<MchPaymentMerchant>lambdaQuery()
                .eq(MchPaymentMerchant::getEntityNo, entityNo)
                .eq(MchPaymentMerchant::getPayMerchantNo, payMerchantNo).last("LIMIT 1")));
    }

    @Override
    public Optional<MchPaymentMerchant> activeByPayMerchantNo(String entityNo, String payMerchantNo, String channel) {
        return Optional.ofNullable(payments.selectOne(Wrappers.<MchPaymentMerchant>lambdaQuery()
                .eq(MchPaymentMerchant::getEntityNo, entityNo)
                .eq(MchPaymentMerchant::getPayMerchantNo, payMerchantNo)
                .eq(MchPaymentMerchant::getPayChannel, channel)
                .eq(MchPaymentMerchant::getApplyStatus, MchPaymentMerchant.ACTIVE)));
    }

    @Override
    public Optional<String> activePayMerchantNoForStore(String entityNo, String storeNo) {
        var own = payments.selectOne(Wrappers.<MchPaymentMerchant>lambdaQuery()
                .eq(MchPaymentMerchant::getEntityNo, entityNo)
                .eq(MchPaymentMerchant::getStoreNo, storeNo)
                .eq(MchPaymentMerchant::getApplyStatus, MchPaymentMerchant.ACTIVE)
                .last("LIMIT 1"));
        if (own != null) {
            return Optional.ofNullable(own.getPayMerchantNo());
        }
        var fallback = payments.selectOne(Wrappers.<MchPaymentMerchant>lambdaQuery()
                .eq(MchPaymentMerchant::getEntityNo, entityNo)
                .eq(MchPaymentMerchant::getStoreNo, MchPaymentMerchant.ENTITY_LEVEL)
                .eq(MchPaymentMerchant::getApplyStatus, MchPaymentMerchant.ACTIVE)
                .last("LIMIT 1"));
        return fallback == null ? Optional.empty() : Optional.ofNullable(fallback.getPayMerchantNo());
    }

    @Override
    public Optional<MchPaymentMerchant> forStoreAndChannel(String entityNo, String storeNo, String channel) {
        if (storeNo != null && !storeNo.isBlank()) {
            MchPaymentMerchant own = payments.selectOne(Wrappers.<MchPaymentMerchant>lambdaQuery()
                    .eq(MchPaymentMerchant::getEntityNo, entityNo)
                    .eq(MchPaymentMerchant::getStoreNo, storeNo)
                    .eq(MchPaymentMerchant::getPayChannel, channel)
                    .last("LIMIT 1"));
            if (own != null) {
                return Optional.of(own);
            }
        }
        return Optional.ofNullable(payments.selectOne(Wrappers.<MchPaymentMerchant>lambdaQuery()
                .eq(MchPaymentMerchant::getEntityNo, entityNo)
                .eq(MchPaymentMerchant::getStoreNo, MchPaymentMerchant.ENTITY_LEVEL)
                .eq(MchPaymentMerchant::getPayChannel, channel)
                .last("LIMIT 1")));
    }

    @Override
    public Optional<MchPaymentMerchant> firstForStore(String entityNo, String storeNoOrEntityLevel) {
        return Optional.ofNullable(payments.selectOne(Wrappers.<MchPaymentMerchant>lambdaQuery()
                .eq(MchPaymentMerchant::getEntityNo, entityNo)
                .eq(MchPaymentMerchant::getStoreNo, storeNoOrEntityLevel)
                .last("LIMIT 1")));
    }

    @Override
    public Page<MchPaymentMerchant> pageForOps(Collection<String> statuses, String payChannel, String keyword,
            Collection<String> entityNosMatchingName, long page, long size) {
        var w = Wrappers.<MchPaymentMerchant>lambdaQuery();
        if (statuses.size() == 1) {
            w.eq(MchPaymentMerchant::getApplyStatus, statuses.iterator().next());
        } else if (!statuses.isEmpty()) {
            w.in(MchPaymentMerchant::getApplyStatus, statuses);
        }
        w.eq(payChannel != null && !payChannel.isBlank(), MchPaymentMerchant::getPayChannel, payChannel);
        if (keyword != null && !keyword.isBlank()) {
            w.and(x -> {
                x.like(MchPaymentMerchant::getEntityNo, keyword)
                        .or().like(MchPaymentMerchant::getPayMerchantNo, keyword)
                        .or().like(MchPaymentMerchant::getSubMchid, keyword);
                if (!entityNosMatchingName.isEmpty()) {
                    x.or().in(MchPaymentMerchant::getEntityNo, entityNosMatchingName);
                }
            });
        }
        // 卡住的（APPLYING/REJECTED）最需要人管，字典序恰好把它们排到 ACTIVE/NONE 前面；同态内新→旧
        w.orderByAsc(MchPaymentMerchant::getApplyStatus).orderByDesc(MchPaymentMerchant::getId);
        return payments.selectPage(Page.of(page, size), w);
    }

    @Override
    public void insert(MchPaymentMerchant row) {
        payments.insert(row);
    }

    @Override
    public void update(MchPaymentMerchant row) {
        payments.updateById(row);
    }

    /*
     * 此前是「读出来 → 内存里加 → updateById」：带 @Version，并发回调时后到的那条撞乐观锁、
     * 返回 0，而返回值没人看 —— 10 个并发回调实测只记上 1 笔，小微超限就这样漏过去。
     * 也不用「失败重读重试」：这里常在支付回调的事务里调用，REPEATABLE READ 下重读拿到的还是旧快照。
     */
    @Override
    public int addQuotaUsed(long id, String period, long amountMinor) {
        return payments.update(null, Wrappers.<MchPaymentMerchant>lambdaUpdate()
                .setSql("quota_used_minor = CASE WHEN quota_period = {0} THEN COALESCE(quota_used_minor, 0) + {1} ELSE {1} END",
                        period, amountMinor)
                .set(MchPaymentMerchant::getQuotaPeriod, period)
                .setSql("version = COALESCE(version, 0) + 1")
                .eq(MchPaymentMerchant::getId, id));
    }
}
