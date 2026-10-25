package ai.neargo.shop.pay.merchant.backfill.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.pay.channel.entity.PayChannelProduct;
import ai.neargo.shop.pay.mapper.ChannelMappers.PayChannelProductMapper;
import ai.neargo.shop.pay.mapper.merchant.MerchantMappers.PayMerchantMapper;
import ai.neargo.shop.pay.mapper.merchant.MerchantMappers.PayMerchantProductMapper;
import ai.neargo.shop.pay.mapper.onboarding.OnboardingMappers.PayOnboardingMapper;
import ai.neargo.shop.pay.mapper.onboarding.OnboardingMappers.PayOnboardingVersionMapper;
import ai.neargo.shop.pay.merchant.backfill.PayMerchantBackfill;
import ai.neargo.shop.pay.merchant.entity.PayMerchant;
import ai.neargo.shop.pay.merchant.entity.PayMerchantProduct;
import ai.neargo.shop.pay.onboarding.entity.PayOnboarding;
import ai.neargo.shop.pay.onboarding.entity.PayOnboardingVersion;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** {@link PayMerchantBackfill} 的实现。一行旧收款商户一个事务 */
@Service
public class PayMerchantBackfillImpl implements PayMerchantBackfill {

    private final PayMerchantMapper merchants;
    private final PayMerchantProductMapper merchantProducts;
    private final PayOnboardingMapper onboardings;
    private final PayOnboardingVersionMapper versions;
    private final PayChannelProductMapper products;
    private final TransactionTemplate tx;

    public PayMerchantBackfillImpl(PayMerchantMapper merchants, PayMerchantProductMapper merchantProducts,
                                   PayOnboardingMapper onboardings, PayOnboardingVersionMapper versions,
                                   PayChannelProductMapper products,
                                   @Qualifier("payTxManager") PlatformTransactionManager payTxManager) {
        this.merchants = merchants;
        this.merchantProducts = merchantProducts;
        this.onboardings = onboardings;
        this.versions = versions;
        this.products = products;
        this.tx = new TransactionTemplate(payTxManager);
    }

    @Override
    public Long platformMerchant(String payChannel, String channelMerchantId, String market, String currency,
                                 String accessMode) {
        return tx.execute(s -> {
            PayMerchant existing = DataScopeContext.executeWithoutScope(() -> merchants.selectOne(
                    Wrappers.<PayMerchant>lambdaQuery().eq(PayMerchant::getPayChannel, payChannel)
                            .eq(PayMerchant::getChannelMerchantId, channelMerchantId).eq(PayMerchant::getSubMerchantId, "")));
            if (existing != null) {
                return existing.getId();
            }
            PayMerchant m = new PayMerchant();
            m.setPayMerchantNo("PLATFORM-" + payChannel + "-" + channelMerchantId);
            m.setOwnerType("PLATFORM");
            m.setPayChannel(payChannel);
            m.setMarket(market);
            m.setCurrency(currency);
            // 平台级商户用平台自己的凭据：是它自己的号（MERCHANT_OWN），不再有父商户
            m.setAccessMode("MERCHANT_OWN");
            m.setFundsPath("AGGREGATED");
            m.setChannelMerchantId(channelMerchantId);
            m.setSubMerchantId("");
            m.setSecKeyVersion(0);
            m.setStatus("ACTIVE");
            m.setInvoiceCapable(false);
            m.setPriority(100);
            m.setActivatedAt(LocalDateTime.now(ZoneOffset.UTC));
            m.setActivatedBy("BACKFILL-B1");
            DataScopeContext.executeWithoutScope(() -> merchants.insert(m));
            return m.getId();
        });
    }

    @Override
    public Result merchant(LegacyMerchant r) {
        return tx.execute(s -> {
            List<String> conflicts = new ArrayList<>();
            PayOnboarding o = onboarding(r);
            if (r.payMerchantNo() == null || r.payMerchantNo().isBlank()) {
                return new Result(null, conflicts);
            }
            PayMerchant existing = DataScopeContext.executeWithoutScope(() -> merchants.selectOne(
                    Wrappers.<PayMerchant>lambdaQuery().eq(PayMerchant::getPayMerchantNo, r.payMerchantNo())));
            PayMerchant m = existing != null ? existing : insertMerchant(r, o, conflicts);
            if (m == null) {
                return new Result(null, conflicts);
            }
            if (o.getPayMerchantId() == null) {
                PayOnboarding link = new PayOnboarding();
                link.setId(o.getId());
                link.setPayMerchantId(m.getId());
                DataScopeContext.executeWithoutScope(() -> onboardings.updateById(link));
            }
            products(r, m, conflicts);
            return new Result(m.getId(), conflicts);
        });
    }

    private PayMerchant insertMerchant(LegacyMerchant r, PayOnboarding o, List<String> conflicts) {
        boolean own = "MERCHANT_OWN".equals(r.accessMode());
        if (r.subMchid() == null || r.subMchid().isBlank()) {
            conflicts.add("NO_SUB_MCHID " + r.payMerchantNo() + "：已开户却没有渠道子商户号");
            return null;
        }
        PayMerchant m = new PayMerchant();
        m.setPayMerchantNo(r.payMerchantNo());
        m.setOwnerType("STORE");
        m.setStoreId(r.storeId());
        m.setPayChannel(r.payChannel());
        m.setMarket(r.market());
        m.setCurrency(r.currency());
        m.setAccessMode(r.accessMode());
        m.setFundsPath(r.fundsPath());
        // 直连：子商户号就是商户自己的渠道商户号；服务商模式：渠道商户号是服务商号，子商户号另列
        m.setChannelMerchantId(own ? r.subMchid() : r.parentChannelMerchantId());
        m.setSubMerchantId(own ? "" : r.subMchid());
        m.setParentPayMerchantId(own ? null : r.parentPayMerchantId());
        m.setPayOnboardingId(o.getId());
        m.setSecKeyVersion(0);
        m.setStatus(switch (r.applyStatus()) {
            case "ACTIVE" -> "ACTIVE";
            case "FROZEN" -> "SUSPENDED";
            default -> "ONBOARDING";
        });
        m.setLegalForm(r.legalForm());
        m.setInvoiceCapable(r.invoiceCapable());
        m.setSettleAccountType(r.settleAccountType());
        m.setSettleAccountMasked(r.settleAccountMasked());
        m.setSettleCycle(r.settleCycle());
        m.setFeeBearer(r.feeBearer());
        m.setSplitReversible(r.splitReversible());
        m.setPriority(100);
        m.setEntityNameSnapshot(r.entityName());
        if ("ACTIVE".equals(m.getStatus())) {
            m.setActivatedAt(LocalDateTime.now(ZoneOffset.UTC));
            m.setActivatedBy("BACKFILL-B2");
        }
        DataScopeContext.executeWithoutScope(() -> merchants.insert(m));
        return m;
    }

    /** 进件单 + v1，按旧行 id 幂等（单号 LEGACY-{id}） */
    private PayOnboarding onboarding(LegacyMerchant r) {
        String no = "LEGACY-" + r.legacyId();
        PayOnboarding existing = DataScopeContext.executeWithoutScope(() -> onboardings.selectOne(
                Wrappers.<PayOnboarding>lambdaQuery().eq(PayOnboarding::getOnboardingNo, no)));
        if (existing != null) {
            return existing;
        }
        String status = switch (r.applyStatus()) {
            case "APPLYING" -> "CHANNEL_REVIEW";
            case "ACTIVE", "FROZEN" -> "APPROVED";
            case "REJECTED" -> "REJECTED";
            default -> "DRAFT";
        };
        PayOnboarding o = new PayOnboarding();
        o.setOnboardingNo(no);
        o.setStoreId(r.storeId());
        o.setPayChannel(r.payChannel());
        o.setMarket(r.market());
        o.setAccessMode(r.accessMode());
        o.setFundsPath(r.fundsPath());
        o.setParentPayMerchantId("MERCHANT_OWN".equals(r.accessMode()) ? null : r.parentPayMerchantId());
        o.setLegalForm(r.legalForm());
        o.setCurrentVersion(1);
        o.setStatus(status);
        o.setChannelApplyNo(r.channelApplyNo());
        o.setRejectReason(r.rejectReason());
        o.setEntityNameSnapshot(cut(r.entityName()));
        o.setStoreNameSnapshot("-");
        o.setInitiatorType("BIZ");
        o.setInitiatorNo("BACKFILL-B2");
        // 在途唯一键只给在途单；两行旧数据落到同一门店 × 渠道的在途进件时第二行留空并在报告里出现
        o.setInflightKey("CHANNEL_REVIEW".equals(status) && !inflightTaken(r) ? r.storeId() + "|" + r.payChannel() : null);
        DataScopeContext.executeWithoutScope(() -> onboardings.insert(o));
        PayOnboardingVersion v = new PayOnboardingVersion();
        v.setPayOnboardingId(o.getId());
        v.setVersionIndex(1);
        v.setOutRequestNo(no + "-V1");
        v.setMaterialsSha256(sha256(no));
        v.setStatus(status);
        v.setChannelApplyNo(r.channelApplyNo());
        v.setRejectReason(r.rejectReason());
        v.setSubmittedBy("BACKFILL-B2");
        DataScopeContext.executeWithoutScope(() -> versions.insert(v));
        return o;
    }

    private boolean inflightTaken(LegacyMerchant r) {
        return DataScopeContext.executeWithoutScope(() -> onboardings.selectCount(Wrappers.<PayOnboarding>lambdaQuery()
                .eq(PayOnboarding::getInflightKey, r.storeId() + "|" + r.payChannel()))) > 0;
    }

    /** B4：旧 pay_methods 逐项映射到支付产品（渠道 × 接入模式 × 渠道方式码）；额度落主产品（JSAPI） */
    private void products(LegacyMerchant r, PayMerchant m, List<String> conflicts) {
        for (String method : r.payMethods()) {
            PayChannelProduct p = DataScopeContext.executeWithoutScope(() -> products.selectOne(
                    Wrappers.<PayChannelProduct>lambdaQuery().eq(PayChannelProduct::getPayChannel, r.payChannel())
                            .eq(PayChannelProduct::getAccessMode, m.getAccessMode())
                            .eq(PayChannelProduct::getChannelMethod, method).last("LIMIT 1")));
            if (p == null) {
                conflicts.add("METHOD " + r.payMerchantNo() + " " + r.payChannel() + "/" + m.getAccessMode() + "/" + method
                        + "：映射不到支付产品");
                continue;
            }
            boolean exists = DataScopeContext.executeWithoutScope(() -> merchantProducts.selectCount(
                    Wrappers.<PayMerchantProduct>lambdaQuery().eq(PayMerchantProduct::getPayMerchantId, m.getId())
                            .eq(PayMerchantProduct::getPayChannelProductId, p.getId()))) > 0;
            if (exists) {
                continue;
            }
            PayMerchantProduct mp = new PayMerchantProduct();
            mp.setPayMerchantId(m.getId());
            mp.setPayChannelProductId(p.getId());
            mp.setStatus("OPEN");
            mp.setSource("CHANNEL_RECEIPT");
            boolean main = Objects.equals(method, "JSAPI");
            mp.setQuotaLimitMinor(main ? r.quotaLimitMinor() : 0L);
            mp.setQuotaUsedMinor(main ? r.quotaUsedMinor() : 0L);
            mp.setQuotaPeriod(main ? r.quotaPeriod() : null);
            mp.setOpenedAt(LocalDateTime.now(ZoneOffset.UTC));
            DataScopeContext.executeWithoutScope(() -> merchantProducts.insert(mp));
        }
    }

    private static String cut(String s) {
        return s == null || s.isBlank() ? "-" : s.length() <= 128 ? s : s.substring(0, 128);
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
