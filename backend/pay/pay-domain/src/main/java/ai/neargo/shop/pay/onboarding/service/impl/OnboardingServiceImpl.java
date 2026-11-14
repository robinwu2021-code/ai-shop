package ai.neargo.shop.pay.onboarding.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.channel.entity.PayChannelProduct;
import ai.neargo.shop.pay.mapper.ChannelMappers.PayChannelProductMapper;
import ai.neargo.shop.pay.mapper.merchant.MerchantMappers.PayMerchantMapper;
import ai.neargo.shop.pay.mapper.merchant.MerchantMappers.PayMerchantProductMapper;
import ai.neargo.shop.pay.mapper.merchant.MerchantMappers.PayMerchantStatusLogMapper;
import ai.neargo.shop.pay.mapper.onboarding.OnboardingMappers.PayOnboardingMapper;
import ai.neargo.shop.pay.mapper.onboarding.OnboardingMappers.PayOnboardingMaterialMapper;
import ai.neargo.shop.pay.mapper.onboarding.OnboardingMappers.PayOnboardingVersionMapper;
import ai.neargo.shop.pay.merchant.MerchantDefaults;
import ai.neargo.shop.pay.merchant.backfill.PayMerchantBackfill;
import ai.neargo.shop.pay.merchant.entity.PayMerchant;
import ai.neargo.shop.pay.merchant.entity.PayMerchantProduct;
import ai.neargo.shop.pay.merchant.entity.PayMerchantStatusLog;
import ai.neargo.shop.pay.merchant.service.PayMerchantBindingService;
import ai.neargo.shop.pay.onboarding.entity.PayOnboarding;
import ai.neargo.shop.pay.onboarding.entity.PayOnboardingMaterial;
import ai.neargo.shop.pay.onboarding.entity.PayOnboardingVersion;
import ai.neargo.shop.pay.onboarding.service.OnboardingService;
import ai.neargo.shop.spi.pay.PayApplymentGateway;
import ai.neargo.shop.spi.pay.PayMerchantQueryPort;
import ai.neargo.shop.spi.user.MerchantQueryPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** {@link OnboardingService} 的实现。渠道调用在事务外，前后各一个短事务 */
@Service
public class OnboardingServiceImpl implements OnboardingService {

    private static final Logger log = LoggerFactory.getLogger(OnboardingServiceImpl.class);

    private static final Set<String> INFLIGHT = Set.of("SUBMITTED", "CHANNEL_REVIEW", "NEED_SIGN");
    private static final String LEGACY_PREFIX = "LEGACY-";
    /** 通道驳回却不给原因时的兜底：如实说没给，不编原因（与旧进件同一条口径） */
    private static final String NO_REASON = "通道未说明驳回原因。请联系平台客服协助查询，不要重复提交同一份资料 ——"
            + "重复进件会产生新的收款商户号，而历史订单的分账仍指向旧号。";

    private final PayOnboardingMapper onboardings;
    private final PayOnboardingVersionMapper versions;
    private final PayOnboardingMaterialMapper materials;
    private final PayMerchantMapper merchants;
    private final PayMerchantProductMapper merchantProducts;
    private final PayMerchantStatusLogMapper statusLogs;
    private final PayChannelProductMapper products;
    private final PayMerchantBackfill platform;
    private final PayMerchantBindingService bindings;
    private final PayMerchantQueryPort routes;
    private final MerchantQueryPort stores;
    private final Map<String, PayApplymentGateway> gateways;
    private final String wechatMode;
    private final String wechatMchid;
    private final TransactionTemplate tx;

    public OnboardingServiceImpl(PayOnboardingMapper onboardings, PayOnboardingVersionMapper versions,
                                 PayOnboardingMaterialMapper materials, PayMerchantMapper merchants,
                                 PayMerchantProductMapper merchantProducts, PayMerchantStatusLogMapper statusLogs,
                                 PayChannelProductMapper products, PayMerchantBackfill platform,
                                 PayMerchantBindingService bindings, PayMerchantQueryPort routes,
                                 MerchantQueryPort stores, List<PayApplymentGateway> gatewayList,
                                 @Value("${shop.pay.stub-channels:WECHAT,ALIPAY}") List<String> stubChannels,
                                 @Value("${shop.pay.wechat.mode:direct}") String wechatMode,
                                 @Value("${shop.pay.wechat.mchid:}") String wechatMchid,
                                 @Qualifier("payTxManager") PlatformTransactionManager payTxManager) {
        this.onboardings = onboardings;
        this.versions = versions;
        this.materials = materials;
        this.merchants = merchants;
        this.merchantProducts = merchantProducts;
        this.statusLogs = statusLogs;
        this.products = products;
        this.platform = platform;
        this.bindings = bindings;
        this.routes = routes;
        this.stores = stores;
        // 与旧进件同一条规则（I1 F05）：真网关优先；stub 只替点了名的渠道，没点名的照样「未接通」
        Map<String, PayApplymentGateway> byChannel = new HashMap<>();
        gatewayList.forEach(g -> byChannel.put(g.payChannel(), g));
        PayApplymentGateway stub = byChannel.get("STUB");
        if (stub != null) {
            stubChannels.stream().map(String::trim).filter(c -> !c.isEmpty()).forEach(c -> byChannel.putIfAbsent(c, stub));
        }
        this.gateways = Map.copyOf(byChannel);
        this.wechatMode = wechatMode;
        this.wechatMchid = wechatMchid;
        this.tx = new TransactionTemplate(payTxManager);
    }

    @Override
    public String open(Long storeId, String payChannel, String initiatorType, String initiatorNo) {
        var store = stores.payStoreById(storeId)
                .orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND));
        String market = store.market();
        String currency = MerchantDefaults.currencyOf(market);
        if (currency == null) {
            throw BizException.of(ErrorCode.PAY_ONBOARDING_STATE, "市场 " + market + " 还没有本币配置");
        }
        String accessMode = MerchantDefaults.accessModeOf(payChannel, wechatMode);
        Long parentId = "MERCHANT_OWN".equals(accessMode) ? null
                : platform.platformMerchant(payChannel, MerchantDefaults.platformChannelMerchantId(payChannel, wechatMchid),
                        market, currency, accessMode);
        PayOnboarding o = new PayOnboarding();
        o.setOnboardingNo(BizKey.next(BizKey.PAY_ONBOARDING));
        o.setStoreId(storeId);
        o.setPayChannel(payChannel);
        o.setMarket(market);
        o.setAccessMode(accessMode);
        o.setFundsPath(store.fundsPath() == null ? "AGGREGATED" : store.fundsPath());
        o.setParentPayMerchantId(parentId);
        // 0 = 还没提交过；首提是第 1 版
        o.setCurrentVersion(0);
        o.setStatus("DRAFT");
        o.setEntityNameSnapshot(cut(store.entityName()));
        o.setStoreNameSnapshot(cut(store.storeName()));
        o.setInitiatorType(initiatorType);
        o.setInitiatorNo(initiatorNo);
        DataScopeContext.executeWithoutScope(() -> onboardings.insert(o));
        return o.getOnboardingNo();
    }

    @Override
    public void submit(String onboardingNo, Materials m, String operatorNo) {
        PayOnboarding o = require(onboardingNo);
        if (!"DRAFT".equals(o.getStatus()) && !"REJECTED".equals(o.getStatus())) {
            throw BizException.of(ErrorCode.PAY_ONBOARDING_STATE, "状态 " + o.getStatus() + " 不能提交");
        }
        PayApplymentGateway gateway = gateway(o.getPayChannel());
        var store = stores.payStoreById(o.getStoreId())
                .orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND));
        int index = o.getCurrentVersion() + 1;
        String outRequestNo = o.getOnboardingNo() + "-V" + index;
        String previousStatus = o.getStatus();
        String previousReason = o.getRejectReason();
        LocalDateTime now = now();
        Long versionId;
        try {
            versionId = tx.execute(s -> claim(o, m, index, outRequestNo, operatorNo, now));
        } catch (DuplicateKeyException ex) {
            throw BizException.of(ErrorCode.PAY_ONBOARDING_INFLIGHT);
        }
        String applyNo;
        try {
            applyNo = gateway.submit(new PayApplymentGateway.SubmitCommand(store.entityNo(), store.entityName(),
                    m.legalForm(), m.contactName(), m.contactPhone(), m.licenses(), m.settleAccountType(),
                    m.settleAccount(), store.storeNo(), outRequestNo));
        } catch (RuntimeException ex) {
            tx.executeWithoutResult(s -> release(o, versionId, index, previousStatus, previousReason));
            throw ex;
        }
        tx.executeWithoutResult(s -> {
            DataScopeContext.executeWithoutScope(() -> onboardings.update(null, Wrappers.<PayOnboarding>lambdaUpdate()
                    .eq(PayOnboarding::getId, o.getId())
                    .set(PayOnboarding::getStatus, "CHANNEL_REVIEW")
                    .set(PayOnboarding::getChannelApplyNo, applyNo)));
            DataScopeContext.executeWithoutScope(() -> versions.update(null, Wrappers.<PayOnboardingVersion>lambdaUpdate()
                    .eq(PayOnboardingVersion::getId, versionId)
                    .set(PayOnboardingVersion::getStatus, "CHANNEL_REVIEW")
                    .set(PayOnboardingVersion::getChannelApplyNo, applyNo)));
        });
    }

    /**
     * 占在途键 + 另起一版 + 落资料，同一事务。在途键唯一（R06）：并发两次提交只有一个占得上，另一个整体回滚。
     * 当前驳回原因显式置空（R23：updateById 跳过 null），上一版的原因原样留着。
     */
    private Long claim(PayOnboarding o, Materials m, int index, String outRequestNo, String operatorNo,
                       LocalDateTime now) {
        int updated = DataScopeContext.executeWithoutScope(() -> onboardings.update(null,
                Wrappers.<PayOnboarding>lambdaUpdate()
                        .eq(PayOnboarding::getId, o.getId())
                        .in(PayOnboarding::getStatus, "DRAFT", "REJECTED")
                        .set(PayOnboarding::getStatus, "SUBMITTED")
                        .set(PayOnboarding::getInflightKey, o.getStoreId() + "|" + o.getPayChannel())
                        .set(PayOnboarding::getCurrentVersion, index)
                        .set(PayOnboarding::getLegalForm, m.legalForm())
                        .set(PayOnboarding::getRejectReason, null)
                        .set(PayOnboarding::getChannelApplyNo, null)
                        .set(PayOnboarding::getSubmittedAt, now)));
        if (updated != 1) {
            throw BizException.of(ErrorCode.PAY_ONBOARDING_STATE, "进件单刚被别处改过，请刷新");
        }
        if (index > 1) {
            DataScopeContext.executeWithoutScope(() -> versions.update(null, Wrappers.<PayOnboardingVersion>lambdaUpdate()
                    .eq(PayOnboardingVersion::getPayOnboardingId, o.getId())
                    .eq(PayOnboardingVersion::getVersionIndex, index - 1)
                    .set(PayOnboardingVersion::getStatus, "SUPERSEDED")));
        }
        Map<String, String> fields = fields(m);
        PayOnboardingVersion v = new PayOnboardingVersion();
        v.setPayOnboardingId(o.getId());
        v.setVersionIndex(index);
        v.setOutRequestNo(outRequestNo);
        v.setMaterialsSha256(sha256(fields.toString()));
        v.setStatus("SUBMITTED");
        v.setSubmittedBy(operatorNo);
        v.setSubmittedAt(now);
        DataScopeContext.executeWithoutScope(() -> versions.insert(v));
        fields.forEach((code, value) -> {
            PayOnboardingMaterial row = new PayOnboardingMaterial();
            row.setPayOnboardingVersionId(v.getId());
            row.setFieldCode(code);
            row.setFieldKind("TEXT");
            row.setValueText(value);
            DataScopeContext.executeWithoutScope(() -> materials.insert(row));
        });
        return v.getId();
    }

    /** 渠道没收下：退回提交前的状态与原因，放开在途键；这一版作废留痕 */
    private void release(PayOnboarding o, Long versionId, int index, String previousStatus, String previousReason) {
        DataScopeContext.executeWithoutScope(() -> onboardings.update(null, Wrappers.<PayOnboarding>lambdaUpdate()
                .eq(PayOnboarding::getId, o.getId())
                .set(PayOnboarding::getStatus, previousStatus)
                .set(PayOnboarding::getInflightKey, null)
                .set(PayOnboarding::getCurrentVersion, index - 1)
                .set(PayOnboarding::getRejectReason, previousReason)
                .set(PayOnboarding::getChannelApplyNo, o.getChannelApplyNo())));
        DataScopeContext.executeWithoutScope(() -> versions.update(null, Wrappers.<PayOnboardingVersion>lambdaUpdate()
                .eq(PayOnboardingVersion::getId, versionId)
                .set(PayOnboardingVersion::getStatus, "SUPERSEDED")
                .set(PayOnboardingVersion::getResultCode, "SUBMIT_FAILED")));
        if (index > 1) {
            DataScopeContext.executeWithoutScope(() -> versions.update(null, Wrappers.<PayOnboardingVersion>lambdaUpdate()
                    .eq(PayOnboardingVersion::getPayOnboardingId, o.getId())
                    .eq(PayOnboardingVersion::getVersionIndex, index - 1)
                    .set(PayOnboardingVersion::getStatus, previousStatus)));
        }
    }

    @Override
    public void refresh(String onboardingNo) {
        PayOnboarding o = require(onboardingNo);
        if (!INFLIGHT.contains(o.getStatus()) || o.getChannelApplyNo() == null) {
            return;
        }
        PayApplymentGateway.ApplymentResult r = gateway(o.getPayChannel()).query(o.getChannelApplyNo());
        if ("ACTIVE".equals(r.status())) {
            if (r.subMchid() == null || r.subMchid().isBlank()) {
                // 开户成功却没给子商户号：产出的收款商户收不了款，宁可停在审核中等人看
                log.error("[onboarding] {} 渠道回 ACTIVE 但没有子商户号，不产出收款商户", onboardingNo);
                return;
            }
            tx.executeWithoutResult(s -> approve(o, r.subMchid()));
        } else if ("REJECTED".equals(r.status())) {
            boolean silent = r.rejectReason() == null || r.rejectReason().isBlank();
            if (silent) {
                log.warn("[onboarding] {} 渠道 {} 驳回但没给原因", onboardingNo, o.getPayChannel());
            }
            String reason = silent ? NO_REASON : r.rejectReason();
            tx.executeWithoutResult(s -> decide(o, "REJECTED", reason, null));
        }
    }

    /** 通过：产出收款商户（ACTIVE + 状态日志 + 开通产品），门店还没有这个渠道的绑定就绑上 */
    private void approve(PayOnboarding o, String subMchid) {
        boolean own = "MERCHANT_OWN".equals(o.getAccessMode());
        PayMerchant parent = own || o.getParentPayMerchantId() == null ? null
                : DataScopeContext.executeWithoutScope(() -> merchants.selectById(o.getParentPayMerchantId()));
        LocalDateTime now = now();
        PayMerchant m = new PayMerchant();
        m.setPayMerchantNo(BizKey.next(BizKey.PAY_MERCHANT));
        m.setOwnerType("STORE");
        m.setStoreId(o.getStoreId());
        m.setPayChannel(o.getPayChannel());
        m.setMarket(o.getMarket());
        m.setCurrency(MerchantDefaults.currencyOf(o.getMarket()));
        m.setAccessMode(o.getAccessMode());
        m.setFundsPath(o.getFundsPath());
        m.setChannelMerchantId(own ? subMchid : parent == null ? "PLATFORM-" + o.getPayChannel() : parent.getChannelMerchantId());
        m.setSubMerchantId(own ? "" : subMchid);
        m.setParentPayMerchantId(own ? null : o.getParentPayMerchantId());
        m.setPayOnboardingId(o.getId());
        m.setSecKeyVersion(0);
        m.setStatus("ACTIVE");
        m.setLegalForm(o.getLegalForm());
        m.setInvoiceCapable(false);
        m.setPriority(100);
        m.setEntityNameSnapshot(o.getEntityNameSnapshot());
        m.setActivatedAt(now);
        m.setActivatedBy("ONBOARDING");
        DataScopeContext.executeWithoutScope(() -> merchants.insert(m));
        PayMerchantStatusLog l = new PayMerchantStatusLog();
        l.setPayMerchantId(m.getId());
        l.setFromStatus("ONBOARDING");
        l.setToStatus("ACTIVE");
        l.setReason("进件 " + o.getOnboardingNo() + " 通过");
        l.setOperatorNo("ONBOARDING");
        l.setChangedAt(now);
        DataScopeContext.executeWithoutScope(() -> statusLogs.insert(l));
        openProducts(m, now);
        decide(o, "APPROVED", null, m.getId());
        if (routes.routeOf(o.getStoreId(), o.getPayChannel()).isEmpty()) {
            bindings.bind(o.getStoreId(), o.getPayChannel(), m.getId(), "ONBOARDING");
        } else {
            // 门店已经在用别的收款商户：换不换由人决定，不在进件通过时悄悄改钱的去向
            log.warn("[onboarding] {} 通过，但门店 {} 在 {} 已有绑定，未改绑", o.getOnboardingNo(), o.getStoreId(),
                    o.getPayChannel());
        }
    }

    /** 通道支持的、本接入模式下启用的支付产品全部开通；额度由运营后配 */
    private void openProducts(PayMerchant m, LocalDateTime now) {
        DataScopeContext.executeWithoutScope(() -> products.selectList(Wrappers.<PayChannelProduct>lambdaQuery()
                        .eq(PayChannelProduct::getPayChannel, m.getPayChannel())
                        .eq(PayChannelProduct::getAccessMode, m.getAccessMode())
                        .eq(PayChannelProduct::getStatus, "ENABLED")))
                .forEach(p -> {
                    PayMerchantProduct mp = new PayMerchantProduct();
                    mp.setPayMerchantId(m.getId());
                    mp.setPayChannelProductId(p.getId());
                    mp.setStatus("OPEN");
                    mp.setSource("CHANNEL_RECEIPT");
                    mp.setQuotaLimitMinor(0L);
                    mp.setQuotaUsedMinor(0L);
                    mp.setOpenedAt(now);
                    DataScopeContext.executeWithoutScope(() -> merchantProducts.insert(mp));
                });
    }

    /** 终态：进件单与当前版一起落；驳回原因写在两处（当前原因给商家看，版本上的留作历史） */
    private void decide(PayOnboarding o, String status, String reason, Long payMerchantId) {
        LocalDateTime now = now();
        DataScopeContext.executeWithoutScope(() -> onboardings.update(null, Wrappers.<PayOnboarding>lambdaUpdate()
                .eq(PayOnboarding::getId, o.getId())
                .set(PayOnboarding::getStatus, status)
                .set(PayOnboarding::getRejectReason, reason)
                .set(PayOnboarding::getInflightKey, null)
                .set(PayOnboarding::getDecidedAt, now)
                .set(payMerchantId != null, PayOnboarding::getPayMerchantId, payMerchantId)));
        DataScopeContext.executeWithoutScope(() -> versions.update(null, Wrappers.<PayOnboardingVersion>lambdaUpdate()
                .eq(PayOnboardingVersion::getPayOnboardingId, o.getId())
                .eq(PayOnboardingVersion::getVersionIndex, o.getCurrentVersion())
                .set(PayOnboardingVersion::getStatus, status)
                .set(PayOnboardingVersion::getRejectReason, reason)
                .set(PayOnboardingVersion::getDecidedAt, now)));
    }

    @Override
    public PollResult poll(int limit) {
        List<String> due = DataScopeContext.executeWithoutScope(() -> onboardings.selectList(
                        Wrappers.<PayOnboarding>lambdaQuery()
                                .isNotNull(PayOnboarding::getInflightKey)
                                .isNotNull(PayOnboarding::getChannelApplyNo)
                                .notLikeRight(PayOnboarding::getOnboardingNo, LEGACY_PREFIX)
                                .orderByAsc(PayOnboarding::getSubmittedAt)
                                .last("LIMIT " + Math.max(1, limit))))
                .stream().map(PayOnboarding::getOnboardingNo).toList();
        int settled = 0;
        int failed = 0;
        for (String no : due) {
            try {
                refresh(no);
                if (!INFLIGHT.contains(require(no).getStatus())) {
                    settled++;
                }
            } catch (RuntimeException ex) {
                failed++;
                log.warn("[onboarding] 轮询 {} 失败：{}", no, ex.toString());
            }
        }
        return new PollResult(due.size(), settled, failed);
    }

    /** 找进件单；回填来的 LEGACY 单归旧进件流程（双写镜像），这里一律不碰 */
    private PayOnboarding require(String onboardingNo) {
        if (onboardingNo != null && onboardingNo.startsWith(LEGACY_PREFIX)) {
            throw BizException.of(ErrorCode.PAY_ONBOARDING_STATE, "回填来的旧进件单由旧进件流程推进");
        }
        return Optional.ofNullable(DataScopeContext.executeWithoutScope(() -> onboardings.selectOne(
                        Wrappers.<PayOnboarding>lambdaQuery().eq(PayOnboarding::getOnboardingNo, onboardingNo))))
                .orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND));
    }

    private PayApplymentGateway gateway(String payChannel) {
        // 没有对应实现直接失败，不回落别的渠道（I1 F05）
        return Optional.ofNullable(gateways.get(payChannel))
                .orElseThrow(() -> BizException.of(ErrorCode.PAY_CHANNEL_UNAVAILABLE));
    }

    /**
     * 一字段一行。结算账号<b>只存掩码</b>：敏感字段要密文入密钥模块（{@code ck_pay_onboarding_material_1}），
     * 密钥模块就绪前明文只在提交那一刻交给渠道（TDD 偏差）。
     */
    private static Map<String, String> fields(Materials m) {
        Map<String, String> f = new LinkedHashMap<>();
        put(f, "LEGAL_FORM", m.legalForm());
        put(f, "CONTACT_NAME", m.contactName());
        put(f, "CONTACT_MOBILE", m.contactPhone());
        if (m.licenses() != null) {
            for (int i = 0; i < m.licenses().size(); i++) {
                put(f, "LICENSE_" + (i + 1), m.licenses().get(i));
            }
        }
        put(f, "SETTLE_ACCOUNT_TYPE", m.settleAccountType());
        put(f, "SETTLE_ACCOUNT_MASKED", mask(m.settleAccount()));
        return f;
    }

    private static void put(Map<String, String> f, String code, String value) {
        if (value != null && !value.isBlank()) {
            f.put(code, value.length() <= 1024 ? value : value.substring(0, 1024));
        }
    }

    private static String mask(String account) {
        if (account == null || account.isBlank()) {
            return null;
        }
        String a = account.replaceAll("\\s", "");
        return a.length() <= 4 ? "****" : "****" + a.substring(a.length() - 4);
    }

    private static String cut(String s) {
        return s == null || s.isBlank() ? "-" : s.length() <= 128 ? s : s.substring(0, 128);
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
