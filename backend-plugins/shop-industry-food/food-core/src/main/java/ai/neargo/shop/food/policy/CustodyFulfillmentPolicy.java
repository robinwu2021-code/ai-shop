package ai.neargo.shop.food.policy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.dto.KitchenContracts.CustodyReceiptVO;
import ai.neargo.shop.spi.trade.IndustryFulfillmentPort.ItemTarget;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Cumulative original quantities from immutable physical custody facts, without reconstructing SKU
 * demand.
 */
public final class CustodyFulfillmentPolicy {
    private CustodyFulfillmentPolicy() {}

    public static List<ItemTarget> targets(
            List<CustodyReceiptVO> all,
            CustodyReceiptVO selected,
            Map<String, BigDecimal> originalMain) {
        var cumulative = new HashMap<String, BigDecimal>();
        boolean found = false;
        for (var receipt : all) {
            if (!"CUSTODY_HANDOVER".equals(receipt.action())) continue;
            if (!selected.entityNo().equals(receipt.entityNo())
                    || !selected.storeNo().equals(receipt.storeNo())
                    || !selected.batchNo().equals(receipt.batchNo())
                    || !selected.bindingNo().equals(receipt.bindingNo())) throw conflict();
            receipt.lines()
                    .forEach(l -> cumulative.merge(l.foodLineNo(), l.qty(), BigDecimal::add));
            if (receipt.operationNo().equals(selected.operationNo())) {
                found = true;
                break;
            }
        }
        if (!found) throw conflict();
        var targets = new LinkedHashMap<Long, ItemTarget>();
        for (var source : selected.sources()) {
            var qty = cumulative.get(source.foodLineNo());
            var main = originalMain.get(source.foodLineNo());
            if (qty == null
                    || main == null
                    || main.signum() <= 0
                    || qty.signum() <= 0
                    || qty.compareTo(main) > 0) throw conflict();
            for (var line : source.originalLines()) {
                BigDecimal actual;
                try {
                    actual =
                            qty.multiply(line.originalQty())
                                    .divide(main, 6, RoundingMode.UNNECESSARY);
                } catch (ArithmeticException precision) {
                    throw conflict();
                }
                var target =
                        new ItemTarget(
                                line.orderItemId(),
                                line.sourceComponentRef(),
                                actual.stripTrailingZeros(),
                                line.unit());
                if (targets.putIfAbsent(line.orderItemId(), target) != null) throw conflict();
            }
        }
        if (targets.isEmpty()) throw conflict();
        return List.copyOf(targets.values());
    }

    private static BizException conflict() {
        return BizException.of(ErrorCode.CONFLICT);
    }
}
