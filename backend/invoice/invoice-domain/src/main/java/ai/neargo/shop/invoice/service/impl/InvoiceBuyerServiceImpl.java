package ai.neargo.shop.invoice.service.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.invoice.dto.InvoiceDtos.CarrierVO;
import ai.neargo.shop.invoice.dto.InvoiceDtos.ChoiceResultVO;
import ai.neargo.shop.invoice.dto.InvoiceDtos.InvoiceChoiceReq;
import ai.neargo.shop.invoice.dto.InvoiceDtos.SaveCarrierReq;
import ai.neargo.shop.invoice.entity.IvcRegimeProfile;
import ai.neargo.shop.invoice.entity.IvcUserCarrier;
import ai.neargo.shop.invoice.mapper.InvoiceMappers.RegimeProfileMapper;
import ai.neargo.shop.invoice.mapper.InvoiceMappers.UserCarrierMapper;
import ai.neargo.shop.invoice.regime.tw.TwBuyerRules;
import ai.neargo.shop.invoice.service.InvoiceBuyerService;
import ai.neargo.shop.invoice.service.InvoiceService;
import ai.neargo.shop.spi.invoice.InvoicePort.Buyer;
import ai.neargo.shop.spi.invoice.InvoicePort.Scope;
import ai.neargo.shop.spi.invoice.InvoiceSourcePort;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * C 端买家的载具与开法（TDD-发票模块 §8）。
 *
 * <p><b>为什么 {@link #choose} 不包一个大事务</b>：一张订单可能拆给几家商家，各家的设定与开立互不相干；
 * 每张子单走 {@link InvoiceService} 自己的事务，一家没开通不影响另一家记下。
 */
@Service
public class InvoiceBuyerServiceImpl implements InvoiceBuyerService {

    private final UserCarrierMapper carriers;
    private final RegimeProfileMapper profiles;
    private final InvoiceService invoices;
    private final List<InvoiceSourcePort> sources;

    public InvoiceBuyerServiceImpl(UserCarrierMapper carriers, RegimeProfileMapper profiles, InvoiceService invoices,
            List<InvoiceSourcePort> sources) {
        this.carriers = carriers;
        this.profiles = profiles;
        this.invoices = invoices;
        this.sources = sources;
    }

    @Override
    public List<CarrierVO> carriers(String tenantNo, String userNo) {
        return carriers.selectList(mine(tenantNo, userNo).orderByDesc("is_default").orderByAsc("id")).stream()
                .map(InvoiceBuyerServiceImpl::view).toList();
    }

    @Override
    @Transactional
    public CarrierVO saveCarrier(String tenantNo, String userNo, SaveCarrierReq req) {
        // 手机条码 / 自然人凭证的格式是财政部定的，与商家无关，所以不经主体税制、直接按台湾规则校验
        TwBuyerRules.validate(new Buyer(null, null, req.carrierType(), req.carrierId(), null), null);
        var now = LocalDateTime.now(ZoneOffset.UTC);
        var row = carriers.selectOne(mine(tenantNo, userNo).eq("carrier_type", req.carrierType())
                .eq("carrier_id", req.carrierId()));
        boolean first = carriers.selectCount(mine(tenantNo, userNo)) == 0;
        boolean makeDefault = first || Boolean.TRUE.equals(req.makeDefault());
        if (makeDefault)
            carriers.update(null, new UpdateWrapper<IvcUserCarrier>().eq("tenant_no", tenantNo).eq("user_no", userNo)
                    .set("is_default", false).set("updated_at", now));
        if (row == null) {
            row = new IvcUserCarrier();
            row.setCarrierNo(BizKey.next(BizKey.INVOICE_CARRIER));
            row.setTenantNo(tenantNo);
            row.setUserNo(userNo);
            row.setCarrierType(req.carrierType());
            row.setCarrierId(req.carrierId());
            row.setLabel(req.label());
            row.setIsDefault(makeDefault);
            row.setCreatedAt(now);
            row.setUpdatedAt(now);
            carriers.insert(row);
        } else {
            row.setLabel(req.label());
            row.setIsDefault(makeDefault || Boolean.TRUE.equals(row.getIsDefault()));
            carriers.update(null, new UpdateWrapper<IvcUserCarrier>().eq("id", row.getId())
                    .set("label", row.getLabel()).set("is_default", row.getIsDefault()).set("updated_at", now));
        }
        return view(row);
    }

    @Override
    @Transactional
    public void deleteCarrier(String tenantNo, String userNo, String carrierNo) {
        if (carriers.delete(mine(tenantNo, userNo).eq("carrier_no", carrierNo)) != 1)
            throw BizException.of(ErrorCode.INVOICE_NOT_FOUND);
    }

    @Override
    public List<ChoiceResultVO> choose(String tenantNo, String userNo, InvoiceChoiceReq req) {
        var found = new ArrayList<InvoiceSourcePort.BuyerSource>();
        for (var port : sources) found.addAll(port.sourcesOfBuyerOrder(tenantNo, userNo, req.orderNo()));
        if (found.isEmpty()) throw BizException.of(ErrorCode.INVOICE_SOURCE_NOT_READY);
        var out = new ArrayList<ChoiceResultVO>();
        for (var s : found) {
            var scope = new Scope(tenantNo, s.entityNo(), s.storeNo(), userNo);
            var issued = invoices.activeOf(scope, s.sourceType(), s.sourceNo());
            if (issued.isPresent()) {
                out.add(new ChoiceResultVO(s.sourceNo(), "ALREADY_ISSUED", issued.get().fiscalNumber()));
                continue;
            }
            var profile = profiles.selectOne(new QueryWrapper<IvcRegimeProfile>().eq("tenant_no", tenantNo)
                    .eq("entity_no", s.entityNo()).eq("status", "ACTIVE").last("LIMIT 1"));
            if (profile == null) {
                out.add(new ChoiceResultVO(s.sourceNo(), "NOT_ENABLED", null));
                continue;
            }
            if ("MEMBER".equals(req.kind()) && profile.getMemberCarrierType() == null) {
                out.add(new ChoiceResultVO(s.sourceNo(), "MEMBER_CARRIER_UNAVAILABLE", null));
                continue;
            }
            invoices.rememberBuyer(scope, s.sourceType(), s.sourceNo(), buyer(req, profile, userNo));
            var now = s.paid() ? autoIssue(scope, s) : null;
            out.add(now == null ? new ChoiceResultVO(s.sourceNo(), "SAVED", null)
                    : new ChoiceResultVO(s.sourceNo(), "ISSUED", now));
        }
        return out;
    }

    /** 已付清的来源：商家开了自动开立就按刚记下的选择开；开不了（金额组成不支持）就只记下，留给商家。 */
    private String autoIssue(Scope scope, InvoiceSourcePort.BuyerSource s) {
        try {
            return invoices.issueIfAutomatic(scope, s.sourceType(), s.sourceNo())
                    .map(r -> r.fiscalNumber()).orElse(null);
        } catch (BizException e) {
            if (e.errorCode() != ErrorCode.INVOICE_SOURCE_NOT_READY) throw e;
            return null;
        }
    }

    private static Buyer buyer(InvoiceChoiceReq req, IvcRegimeProfile profile, String userNo) {
        return switch (req.kind()) {
            case "MEMBER" -> InvoiceServiceImpl.memberCarrier(profile, userNo);
            case "CARRIER" -> new Buyer(null, null, req.carrierType(), req.carrierId(), null);
            case "TAX_ID" -> new Buyer(req.taxId(), req.name(), null, null, null);
            case "DONATE" -> new Buyer(null, null, null, null, req.donateCode());
            default -> Buyer.consumer();
        };
    }

    private static QueryWrapper<IvcUserCarrier> mine(String tenantNo, String userNo) {
        return new QueryWrapper<IvcUserCarrier>().eq("tenant_no", tenantNo).eq("user_no", userNo);
    }

    private static CarrierVO view(IvcUserCarrier c) {
        return new CarrierVO(c.getCarrierNo(), c.getCarrierType(), c.getCarrierId(), c.getLabel(),
                Boolean.TRUE.equals(c.getIsDefault()));
    }
}
