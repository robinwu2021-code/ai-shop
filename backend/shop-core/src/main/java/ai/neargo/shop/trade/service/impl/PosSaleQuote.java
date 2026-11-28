package ai.neargo.shop.trade.service.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.trade.entity.OrdItem;
import ai.neargo.shop.trade.entity.OrdOrder;
import ai.neargo.shop.trade.mapper.PosActivitySaleMapper;
import ai.neargo.shop.trade.mapper.PosActivitySaleMapper.Allocation;
import ai.neargo.shop.trade.mapper.PosActivitySaleMapper.Quote;
import ai.neargo.shop.trade.mapper.PosActivitySaleMapper.QuoteLine;
import ai.neargo.shop.trade.order.service.create.CheckoutDigests;
import java.util.List;

/** Retains the gross amounts and verified POS activity allocations with stable order-line references. */
final class PosSaleQuote {

    record Share(int lineIndex, int step, String applyNo, String activityNo, String version, long basis,
                 long discount) {}

    private PosSaleQuote() {}

    static void record(PosActivitySaleMapper facts, OrdOrder order, String subNo, String memberNo, int schemaVersion,
                       List<OrdItem> lines, List<Share> shares, long[] activityTotals) {
        String quoteNo = BizKey.next("PQ");
        String tenant = order.getTenantNo();
        String orderNo = order.getOrderNo();
        long activity = shares.stream().mapToLong(Share::discount).reduce(0, Math::addExact);
        var quote = new Quote(tenant, quoteNo, orderNo, subNo, order.getEntityNo(), order.getStoreNo(),
                order.getUserNo(), memberNo, schemaVersion, order.getCurrency(), order.getGoodsAmount(),
                activity, Math.subtractExact(order.getDiscountAmount(), activity), order.getPayAmount());
        facts.insertQuote(quote);
        String[] quoteLineNos = new String[lines.size()];
        for (int i = 0; i < lines.size(); i++) {
            String sourceLineNo = BizKey.next("OL");
            String quoteLineNo = BizKey.next("QL");
            var item = lines.get(i);
            requireLinked(facts.linkLine(tenant, subNo, item.getId(), sourceLineNo));
            facts.insertQuoteLine(new QuoteLine(tenant, quoteNo, quoteLineNo, sourceLineNo,
                    CheckoutDigests.hash(sourceLineNo), i, item.getGoodsNo(), item.getSkuNo(), item.getTitle(),
                    item.getTaxClass(), item.getQty(), item.getPrice(), item.getAmount(), activityTotals[i],
                    item.getPaidShareMinor()));
            quoteLineNos[i] = quoteLineNo;
        }
        for (var share : shares) {
            facts.insertAllocation(new Allocation(tenant, BizKey.next("QA"), quoteNo, quoteLineNos[share.lineIndex()],
                    orderNo, subNo, share.step(), share.applyNo(), share.activityNo(), ruleVersion(share.version()),
                    share.version(), share.basis(), share.discount(), order.getCurrency(), order.getUserNo()));
        }
        requireLinked(facts.linkQuote(tenant, subNo, orderNo, quoteNo));
    }

    static void requireLinked(int affected) {
        if (affected != 1) throw BizException.of(ErrorCode.CONFLICT);
    }

    private static String ruleVersion(String version) {
        // Legacy activities emit a SHA-256 hex digest; preserve the full value in allocation evidence.
        return version == null || version.length() <= 32 ? version : version.substring(0, 32);
    }
}
