package ai.neargo.shop.trade.order.refund.service.impl;

import ai.neargo.shop.trade.order.refund.service.OriginalRefundEvidenceService;
import ai.neargo.shop.trade.order.refund.service.BasicRefundPolicy;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.spi.product.StockPort;
import ai.neargo.shop.spi.trade.IndustryOrderPort;
import ai.neargo.shop.spi.trade.PricingCheckoutPort;
import ai.neargo.shop.trade.entity.OrdItem;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.entity.OrdIndustryOrderSource;
import ai.neargo.shop.trade.mapper.TradeMappers.IndustryOrderSourceMapper;
import ai.neargo.shop.trade.order.refund.model.RefundPlan;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** Frozen original VAT evidence and cumulative unissued quantities; never calls pricing or an asset owner. */
@Service
public class OriginalRefundEvidenceServiceImpl implements OriginalRefundEvidenceService {
    private final IndustryOrderSourceMapper sources;
    private final ObjectMapper json;
    public OriginalRefundEvidenceServiceImpl(IndustryOrderSourceMapper sources, ObjectMapper json) {
        this.sources = sources; this.json = json;
    }

    @Override
    public RefundPlan freeze(OrdSubOrder child, List<OrdItem> items, RefundPlan selected) {
        Map<Long, Integer> quantities = new HashMap<>();
        selected.lines().forEach(line -> quantities.put(line.itemId(), line.qty()));
        List<StockPort.SkuQty> targets = new ArrayList<>();
        for (var item : items) {
            int through = Math.addExact(item.getRefundedQty() == null ? 0 : item.getRefundedQty(), quantities.getOrDefault(item.getId(), 0));
            if (through > 0) targets.add(new StockPort.SkuQty(item.getSkuNo(), through,
                    item.getSupplyStoreNo() == null ? child.getStoreNo() : item.getSupplyStoreNo()));
        }
        var source = DataScopeContext.executeWithoutScope(() -> sources.selectOne(Wrappers.<OrdIndustryOrderSource>lambdaQuery()
                .eq(OrdIndustryOrderSource::getOrderNo, child.getOrderNo()).eq(OrdIndustryOrderSource::getSubOrderNo, child.getSubOrderNo())));
        if (source == null) return new RefundPlan(selected.subOrderNo(), selected.lines(), selected.freightMinor());
        if (!"BOUND".equals(source.getStatus()) || !Objects.equals(source.getTenantNo(), child.getTenantNo())
                || !Objects.equals(source.getOrderNo(), child.getOrderNo()) || !Objects.equals(source.getSubOrderNo(), child.getSubOrderNo())
                || !Objects.equals(source.getBuyerNo(), child.getUserNo())
                || !Objects.equals(source.getEntityNo(), child.getEntityNo()) || !Objects.equals(source.getStoreNo(), child.getStoreNo())) throw conflict();
        var envelope = json.readTree(source.getAllocationsJson());
        if (!envelope.path("evidence").isArray() || envelope.path("evidence").size() != items.size()) throw conflict();
        Map<Long, RefundPlan.OriginalPortion> portions = new HashMap<>();
        for (var evidence : envelope.path("evidence")) {
            var allocation = json.convertValue(evidence.path("allocation"), IndustryOrderPort.Allocation.class);
            var price = json.convertValue(evidence.path("price"), PricingCheckoutPort.LineVO.class);
            var item = items.stream().filter(i -> i.getId() == allocation.orderItemId()).findFirst().orElseThrow(OriginalRefundEvidenceServiceImpl::conflict);
            if (item.getPaidShareMinor() == null || allocation.qty() != item.getQty()
                    || allocation.amountMinor() != item.getPaidShareMinor() || price.receivableMinor() != allocation.amountMinor()
                    || price.taxMinor() < 0 || price.netMinor() < 0
                    || Math.addExact(price.netMinor(), price.taxMinor()) != price.receivableMinor()
                    || !Objects.equals(allocation.sourceLineNo(), price.sourceLineNo())
                    || !Objects.equals(allocation.componentRef(), price.componentRef())) throw conflict();
            int before = item.getRefundedQty() == null ? 0 : item.getRefundedQty();
            int through = before + quantities.getOrDefault(item.getId(), 0);
            long startCash = BasicRefundPolicy.prefix(price.receivableMinor(), before, allocation.qty());
            long endCash = BasicRefundPolicy.prefix(price.receivableMinor(), through, allocation.qty());
            // Split the same gross prefix into original tax and net; tax never exceeds this cash portion.
            long startTax = price.receivableMinor() == 0 ? 0 : BasicRefundPolicy.prefix(price.taxMinor(), startCash, price.receivableMinor());
            long endTax = price.receivableMinor() == 0 ? 0 : BasicRefundPolicy.prefix(price.taxMinor(), endCash, price.receivableMinor());
            if (quantities.containsKey(item.getId())) {
                var portion = new RefundPlan.OriginalPortion(item.getId(), allocation.sourceLineNo(), allocation.componentRef(),
                        allocation.qty(), before, through, (endCash-startCash)-(endTax-startTax), endTax-startTax,
                        endCash-startCash, PricingSnapshotHash.of(evidence));
                if (portions.putIfAbsent(item.getId(), portion) != null) throw conflict();
            }
        }
        var ordered = selected.lines().stream().map(line -> {
            var portion = portions.get(line.itemId());
            if (portion == null || portion.cashMinor() != line.amountMinor()) throw conflict();
            return portion;
        }).toList();
        return new RefundPlan(selected.subOrderNo(), selected.lines(), selected.freightMinor(), ordered, targets);
    }
    @Override
    public void verify(OrdSubOrder child, List<OrdItem> items, RefundPlan plan) {
        var expected = freeze(child, items, new RefundPlan(plan.subOrderNo(),plan.lines(),plan.freightMinor()));
        if (!expected.originalPortions().equals(plan.originalPortions())
                || !expected.allocationReleaseTargets().equals(plan.allocationReleaseTargets())) throw conflict();
    }
    private static BizException conflict() { return BizException.of(ErrorCode.AFTERSALE_AMOUNT_INVALID); }
}
