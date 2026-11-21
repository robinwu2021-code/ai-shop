package ai.neargo.shop.invoice.service.impl;

import ai.neargo.secret.api.KeyOwner;
import ai.neargo.secret.api.SecretNamespace;
import ai.neargo.secret.api.SecretVault;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.invoice.dto.InvoiceDtos.NumberRangeReq;
import ai.neargo.shop.invoice.dto.InvoiceDtos.NumberRangeVO;
import ai.neargo.shop.invoice.dto.InvoiceDtos.RegimeProfileReq;
import ai.neargo.shop.invoice.dto.InvoiceDtos.RegimeProfileVO;
import ai.neargo.shop.invoice.entity.IvcNumberRange;
import ai.neargo.shop.invoice.entity.IvcRegimeProfile;
import ai.neargo.shop.invoice.mapper.InvoiceMappers.NumberRangeMapper;
import ai.neargo.shop.invoice.mapper.InvoiceMappers.RegimeProfileMapper;
import ai.neargo.shop.invoice.regime.InvoiceRegimes;
import ai.neargo.shop.invoice.regime.tw.TwQrCipher;
import ai.neargo.shop.invoice.service.InvoiceSetupService;
import ai.neargo.shop.spi.invoice.InvoicePort.Scope;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 税制开通与号段登记。一个主体同一时刻只开通一种税制（首期只有台湾）；换税制即把原配置停用。
 *
 * <p>QR 加密密钥只进 secret 模块（命名空间 PLATFORM），业务表只存引用 —— 拿到库的人拿不到能伪造证明联的钥匙。
 */
@Service
public class InvoiceSetupServiceImpl implements InvoiceSetupService {

    private final RegimeProfileMapper profiles;
    private final NumberRangeMapper ranges;
    private final InvoiceRegimes regimes;
    private final ObjectProvider<SecretVault> vault;

    public InvoiceSetupServiceImpl(RegimeProfileMapper profiles, NumberRangeMapper ranges, InvoiceRegimes regimes,
            ObjectProvider<SecretVault> vault) {
        this.profiles = profiles;
        this.ranges = ranges;
        this.regimes = regimes;
        this.vault = vault;
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
        profiles.updateById(p);
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
        r.setCreatedBy(scope.actorNo());
        r.setCreatedAt(now);
        r.setUpdatedAt(now);
        ranges.insert(r);
        return view(r);
    }

    private static RegimeProfileVO view(IvcRegimeProfile p) {
        return new RegimeProfileVO(p.getRegimeCode(), p.getSellerTaxId(), p.getSellerName(), p.getGatewayCode(),
                p.getQrKeyRef() != null, p.getMemberCarrierType(), Boolean.TRUE.equals(p.getAutoIssue()), p.getStatus());
    }

    private static NumberRangeVO view(IvcNumberRange r) {
        return new NumberRangeVO(r.getRangeNo(), r.getRegimeCode(), r.getStoreNo(), r.getPeriodCode(), r.getTrack(),
                r.getStartNo(), r.getEndNo(), r.getNextNo(), Math.max(0, r.getEndNo() - r.getNextNo() + 1),
                r.getStatus());
    }
}
