package ai.neargo.shop.invoice.service.impl;

import ai.neargo.secret.api.KeyOwner;
import ai.neargo.secret.api.SecretNamespace;
import ai.neargo.secret.api.SecretVault;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.invoice.dto.InvoiceDtos.NumberRangeReq;
import ai.neargo.shop.invoice.dto.InvoiceDtos.NumberRangeVO;
import ai.neargo.shop.invoice.dto.InvoiceDtos.ProviderVerificationVO;
import ai.neargo.shop.invoice.dto.InvoiceDtos.RegimeProfileReq;
import ai.neargo.shop.invoice.dto.InvoiceDtos.RegimeProfileVO;
import ai.neargo.shop.invoice.entity.IvcNumberRange;
import ai.neargo.shop.invoice.entity.IvcRegimeProfile;
import ai.neargo.shop.invoice.gateway.InvoiceGateway;
import ai.neargo.shop.invoice.gateway.InvoiceGateway.ProviderAccount;
import ai.neargo.shop.invoice.gateway.InvoiceProviderGateway;
import ai.neargo.shop.invoice.gateway.InvoiceProviderGateway.ProviderException;
import ai.neargo.shop.invoice.mapper.InvoiceMappers.NumberRangeMapper;
import ai.neargo.shop.invoice.mapper.InvoiceMappers.RegimeProfileMapper;
import ai.neargo.shop.invoice.regime.InvoiceRegimes;
import ai.neargo.shop.invoice.regime.tw.TwQrCipher;
import ai.neargo.shop.invoice.service.InvoiceSetupService;
import ai.neargo.shop.spi.invoice.InvoicePort.Scope;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 税制开通与号段登记。一个主体同一时刻只开通一种税制（首期只有台湾）；换税制即把原配置停用。
 *
 * <p>QR 加密密钥只进 secret 模块（命名空间 PLATFORM），业务表只存引用 —— 拿到库的人拿不到能伪造证明联的钥匙。
 * 加值中心账号同理：代号存业务表，账号密码进 secret（{@link InvoiceProviderAccounts}）。
 *
 * <p>加值中心的核验与取号要出网，方法本身不开事务：远程调用期间不占数据库连接与行锁，回来后单条写入。
 */
@Service
public class InvoiceSetupServiceImpl implements InvoiceSetupService {

    private final RegimeProfileMapper profiles;
    private final NumberRangeMapper ranges;
    private final InvoiceRegimes regimes;
    private final ObjectProvider<SecretVault> vault;
    private final InvoiceProviderAccounts accounts;
    private final List<InvoiceGateway> gateways;

    public InvoiceSetupServiceImpl(RegimeProfileMapper profiles, NumberRangeMapper ranges, InvoiceRegimes regimes,
            ObjectProvider<SecretVault> vault, InvoiceProviderAccounts accounts, List<InvoiceGateway> gateways) {
        this.profiles = profiles;
        this.ranges = ranges;
        this.regimes = regimes;
        this.vault = vault;
        this.accounts = accounts;
        this.gateways = gateways;
    }

    @Override
    public Optional<RegimeProfileVO> profile(Scope scope) {
        return Optional.ofNullable(profiles.selectOne(new QueryWrapper<IvcRegimeProfile>()
                .eq("tenant_no", scope.tenantNo()).eq("entity_no", scope.entityNo()).eq("status", "ACTIVE")
                .last("LIMIT 1"))).map(InvoiceSetupServiceImpl::view);
    }

    @Override
    @Transactional
    public RegimeProfileVO saveProfile(Scope scope, RegimeProfileReq req) {
        var regime = regimes.require(req.regimeCode());
        regime.validateSeller(req.sellerTaxId());
        var now = LocalDateTime.now(ZoneOffset.UTC);
        var p = profiles.selectOne(new QueryWrapper<IvcRegimeProfile>().eq("tenant_no", scope.tenantNo())
                .eq("entity_no", scope.entityNo()).eq("regime_code", regime.code()));
        boolean fresh = p == null;
        if (fresh) {
            p = new IvcRegimeProfile();
            p.setTenantNo(scope.tenantNo());
            p.setEntityNo(scope.entityNo());
            p.setRegimeCode(regime.code());
            p.setCreatedBy(scope.actorNo());
            p.setCreatedAt(now);
        }
        p.setSellerTaxId(req.sellerTaxId());
        p.setSellerName(req.sellerName());
        p.setGatewayCode(req.gatewayCode());
        p.setMemberCarrierType(req.memberCarrierType());
        p.setAutoIssue(Boolean.TRUE.equals(req.autoIssue()));
        p.setProviderAccountCode(blankToNull(req.providerAccountCode()));
        p.setStatus("ACTIVE");
        p.setUpdatedBy(scope.actorNo());
        p.setUpdatedAt(now);
        if (fresh) profiles.insert(p);
        if (req.qrAesKeyHex() != null) {
            var v = vault.getIfAvailable();
            if (v == null) throw BizException.of(ErrorCode.INVOICE_KEY_UNAVAILABLE);
            p.setQrKeyRef(v.seal(new KeyOwner(SecretNamespace.PLATFORM, InvoiceServiceImpl.QR_KEY_OWNER, p.getId()),
                    TwQrCipher.parseKey(req.qrAesKeyHex())).blobId());
        }
        if (req.providerUser() != null)
            p.setProviderSecretRef(accounts.seal(p.getId(), req.providerUser(), req.providerPassword()));
        profiles.updateById(p);
        // updateById 跳过 null 字段：清空会员载具类别号（不再提供会员载具）要显式写 null，否则旧值留着
        if (p.getMemberCarrierType() == null)
            profiles.update(null, new UpdateWrapper<IvcRegimeProfile>().eq("id", p.getId())
                    .set("member_carrier_type", null));
        if (p.getProviderAccountCode() == null)
            profiles.update(null, new UpdateWrapper<IvcRegimeProfile>().eq("id", p.getId())
                    .set("provider_account_code", null));
        // 一个主体同一时刻只开通一种税制
        var others = new IvcRegimeProfile();
        others.setStatus("SUSPENDED");
        profiles.update(others, new QueryWrapper<IvcRegimeProfile>().eq("tenant_no", scope.tenantNo())
                .eq("entity_no", scope.entityNo()).ne("regime_code", regime.code()));
        return view(p);
    }

    @Override
    public List<NumberRangeVO> ranges(Scope scope, String periodCode) {
        return ranges.selectList(new QueryWrapper<IvcNumberRange>().eq("tenant_no", scope.tenantNo())
                        .eq("entity_no", scope.entityNo()).eq(periodCode != null, "period_code", periodCode)
                        .orderByDesc("period_code").orderByAsc("track", "start_no"))
                .stream().map(InvoiceSetupServiceImpl::view).toList();
    }

    @Override
    @Transactional
    public NumberRangeVO addRange(Scope scope, NumberRangeReq req) {
        var regime = regimes.require(req.regimeCode());
        regime.validateRange(req.periodCode(), req.track(), req.startNo(), req.endNo());
        if (ranges.countOverlap(scope.tenantNo(), scope.entityNo(), regime.code(), req.periodCode(), req.track(),
                req.startNo(), req.endNo()) > 0) throw BizException.of(ErrorCode.INVOICE_RANGE_INVALID);
        var now = LocalDateTime.now(ZoneOffset.UTC);
        var r = new IvcNumberRange();
        r.setRangeNo(BizKey.next(BizKey.INVOICE_RANGE));
        r.setTenantNo(scope.tenantNo());
        r.setEntityNo(scope.entityNo());
        r.setRegimeCode(regime.code());
        r.setStoreNo(req.storeNo() == null || req.storeNo().isBlank() ? "*" : req.storeNo());
        r.setPeriodCode(req.periodCode());
        r.setTrack(req.track());
        r.setStartNo(req.startNo());
        r.setEndNo(req.endNo());
        r.setNextNo(req.startNo());
        r.setStatus("ACTIVE");
        r.setAllocationOwner("MANUAL");
        r.setCreatedBy(scope.actorNo());
        r.setCreatedAt(now);
        r.setUpdatedAt(now);
        ranges.insert(r);
        return view(r);
    }

    @Override
    public ProviderVerificationVO verifyProvider(Scope scope) {
        var p = requireProfile(scope);
        var office = call(() -> provider(p).verify(account(p)));
        if (!p.getSellerTaxId().equals(office.taxId()))
            throw BizException.of(ErrorCode.INVOICE_PROVIDER_REJECTED, "tax_id_mismatch");
        byte[] key = TwQrCipher.parseKey(office.qrKey());
        if (key != null) {
            var v = vault.getIfAvailable();
            if (v == null) throw BizException.of(ErrorCode.INVOICE_KEY_UNAVAILABLE);
            long ref = v.seal(new KeyOwner(SecretNamespace.PLATFORM, InvoiceServiceImpl.QR_KEY_OWNER, p.getId()), key)
                    .blobId();
            profiles.update(null, new UpdateWrapper<IvcRegimeProfile>().eq("id", p.getId()).set("qr_key_ref", ref)
                    .set("updated_by", scope.actorNo()).set("updated_at", LocalDateTime.now(ZoneOffset.UTC)));
        }
        return new ProviderVerificationVO(office.taxId(), office.name(), key != null);
    }

    @Override
    public NumberRangeVO fetchProviderRange(Scope scope, String periodCode) {
        var p = requireProfile(scope);
        var regime = regimes.require(p.getRegimeCode());
        String period = periodCode != null ? periodCode : regime.periodOf(Instant.now());
        var got = call(() -> provider(p).allocate(account(p), period));
        var same = ranges.selectOne(new QueryWrapper<IvcNumberRange>().eq("tenant_no", scope.tenantNo())
                .eq("entity_no", scope.entityNo()).eq("regime_code", regime.code()).eq("period_code", period)
                .eq("track", got.track()).eq("start_no", got.startNo()).eq("end_no", got.endNo()).last("LIMIT 1"));
        if (same != null) return view(same);
        regime.validateRange(period, got.track(), got.startNo(), got.endNo());
        if (ranges.countOverlap(scope.tenantNo(), scope.entityNo(), regime.code(), period, got.track(),
                got.startNo(), got.endNo()) > 0) throw BizException.of(ErrorCode.INVOICE_RANGE_INVALID);
        var now = LocalDateTime.now(ZoneOffset.UTC);
        var r = new IvcNumberRange();
        r.setRangeNo(BizKey.next(BizKey.INVOICE_RANGE));
        r.setTenantNo(scope.tenantNo());
        r.setEntityNo(scope.entityNo());
        r.setRegimeCode(regime.code());
        r.setStoreNo("*");
        r.setPeriodCode(period);
        r.setTrack(got.track());
        r.setStartNo(got.startNo());
        r.setEndNo(got.endNo());
        r.setNextNo(got.startNo());
        r.setStatus("ACTIVE");
        r.setAllocationOwner("PROVIDER");
        r.setApplyId(got.applyId());
        r.setInvoiceType(got.invoiceType());
        r.setCreatedBy(scope.actorNo());
        r.setCreatedAt(now);
        r.setUpdatedAt(now);
        ranges.insert(r);
        return view(r);
    }

    private IvcRegimeProfile requireProfile(Scope scope) {
        var p = profiles.selectOne(new QueryWrapper<IvcRegimeProfile>().eq("tenant_no", scope.tenantNo())
                .eq("entity_no", scope.entityNo()).eq("status", "ACTIVE").last("LIMIT 1"));
        if (p == null) throw BizException.of(ErrorCode.INVOICE_REGIME_NOT_ENABLED);
        return p;
    }

    /** 主体配的通道须是加值中心类（要账号、能配号）；STUB / Turnkey 下这两个动作没有意义。 */
    private InvoiceProviderGateway provider(IvcRegimeProfile p) {
        return gateways.stream().filter(g -> g.code().equals(p.getGatewayCode()))
                .filter(InvoiceProviderGateway.class::isInstance).map(InvoiceProviderGateway.class::cast).findFirst()
                .orElseThrow(() -> BizException.of(ErrorCode.INVOICE_STATE_INVALID));
    }

    private ProviderAccount account(IvcRegimeProfile p) {
        return accounts.of(p).orElseThrow(
                () -> BizException.of(ErrorCode.INVOICE_PROVIDER_REJECTED, "account_not_configured"));
    }

    private static <T> T call(Supplier<T> remote) {
        try {
            return remote.get();
        } catch (ProviderException e) {
            // 业务动作：服务商原文代码交给用户对照服务商文档排查（账号错 invalid、本期已申请 exist …）
            throw BizException.of(ErrorCode.INVOICE_PROVIDER_REJECTED, e.code());
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static RegimeProfileVO view(IvcRegimeProfile p) {
        return new RegimeProfileVO(p.getRegimeCode(), p.getSellerTaxId(), p.getSellerName(), p.getGatewayCode(),
                p.getQrKeyRef() != null, p.getMemberCarrierType(), Boolean.TRUE.equals(p.getAutoIssue()), p.getStatus(),
                p.getProviderAccountCode(), p.getProviderSecretRef() != null);
    }

    private static NumberRangeVO view(IvcNumberRange r) {
        return new NumberRangeVO(r.getRangeNo(), r.getRegimeCode(), r.getStoreNo(), r.getPeriodCode(), r.getTrack(),
                r.getStartNo(), r.getEndNo(), r.getNextNo(), Math.max(0, r.getEndNo() - r.getNextNo() + 1),
                r.getStatus(), r.getAllocationOwner() == null ? "MANUAL" : r.getAllocationOwner());
    }
}
