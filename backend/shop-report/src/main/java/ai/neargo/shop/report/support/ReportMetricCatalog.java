package ai.neargo.shop.report.support;

import ai.neargo.shop.spi.report.ReportContracts.Aggregation;
import ai.neargo.shop.spi.report.ReportContracts.MetricFact;
import ai.neargo.shop.spi.report.ReportContracts.Owner;
import ai.neargo.shop.spi.report.ReportContracts.SourceKey;
import ai.neargo.shop.spi.report.ReportContracts.ValueType;

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Fixed first-release catalog, not an executable JSON/SQL rule language. */
public final class ReportMetricCatalog {
    public record Definition(
            String code,
            SourceKey source,
            ValueType type,
            Aggregation aggregation,
            Set<String> sets,
            Set<String> dimensions,
            boolean negativeAllowed) {}

    private final Map<String, Definition> definitions = new LinkedHashMap<>();
    private static final Set<String> DIMENSIONS =
            Set.of(
                    "skuNo",
                    "categoryNo",
                    "componentRole",
                    "menuNo",
                    "menuVersion",
                    "mealPeriod",
                    "channel",
                    "terminal",
                    "fulfillment",
                    "payMode",
                    "paymentMethod",
                    "paymentChannel",
                    "refundChannel",
                    "discountType",
                    "bearer",
                    "stationNo",
                    "materialSkuNo",
                    "purpose",
                    "actorNo",
                    "action",
                    "status",
                    "reason",
                    "materialNo",
                    "ownerId",
                    "locationNo",
                    "costCategory",
                    "recipeVersion",
                    "taskRole",
                    "sourceType");

    public ReportMetricCatalog() {
        add(
                Owner.CORE,
                "ORDER",
                ValueType.COUNT,
                "OPERATING_ORDER_DAY",
                false,
                "ORDER_CREATED_COUNT",
                "ORDER_TRANSACTED_COUNT",
                "ORDER_COMPLETED_COUNT");
        add(
                Owner.CORE,
                "ORDER",
                ValueType.MONEY,
                "OPERATING_ORDER_DAY",
                true,
                "ORDER_ORIGINAL_DUE_AMOUNT",
                "ORDER_EFFECTIVE_DUE_AMOUNT",
                "ORDER_TRANSACTED_AMOUNT",
                "RECEIVABLE_ADJUSTMENT_AMOUNT");
        add(
                Owner.CORE,
                "ORDER",
                ValueType.MONEY,
                "PROFIT_COST_DAY",
                true,
                "GOODS_TRANSACTED_NET_AMOUNT",
                "GOODS_SALES_RETURN_AMOUNT",
                "OPERATING_OTHER_INCOME_AMOUNT",
                "SERVICE_FEE_AMOUNT");
        add(
                Owner.CORE,
                "ORDER",
                ValueType.MONEY,
                "DISCOUNT_TYPE_DAY",
                true,
                "GOODS_DISCOUNT_AMOUNT");
        add(
                Owner.CORE,
                "ORDER",
                ValueType.MONEY,
                "DISH_COMPONENT_DAY",
                true,
                "DISH_ORIGINAL_AMOUNT",
                "ADDON_CHARGE_AMOUNT");
        add(
                Owner.FOOD,
                "ORDER",
                ValueType.COUNT,
                "OPERATING_ORDER_DAY",
                false,
                "ORDER_BATCH_COUNT",
                "BATCH_ACCEPTED_EVENT_COUNT",
                "RETURN_REQUEST_COUNT");
        add(
                Owner.FOOD,
                "ORDER",
                ValueType.QUANTITY,
                "DISH_COMPONENT_DAY",
                false,
                "DISH_ORDERED_QTY",
                "BUNDLE_ORDERED_QTY",
                "BUNDLE_COMPONENT_QTY",
                "ADDON_SELECTED_QTY",
                "DISH_GIFT_QTY");
        add(
                Owner.FOOD,
                "ORDER",
                ValueType.QUANTITY,
                "DISH_FULFILLMENT_DAY",
                false,
                "DISH_CANCELLED_QTY",
                "DISH_DELIVERED_QTY",
                "RETURN_EXECUTED_QTY");
        add(
                Owner.FOOD,
                "KITCHEN",
                ValueType.QUANTITY,
                "KITCHEN_STATION_DAY",
                false,
                "KITCHEN_STARTED_QTY",
                "KITCHEN_READY_QTY",
                "KITCHEN_HANDED_OVER_QTY",
                "KITCHEN_REWORK_QTY",
                "KITCHEN_LOSS_QTY");
        add(
                Owner.FOOD,
                "STAFF",
                ValueType.COUNT,
                "STAFF_ACTION_DAY",
                false,
                "STAFF_ACTION_COUNT",
                "STAFF_APPROVAL_COUNT");
        add(
                Owner.PAY,
                "PAYMENT",
                ValueType.COUNT,
                "PAYMENT_CHANNEL_DAY",
                false,
                "PAYMENT_SUCCESS_COUNT");
        add(
                Owner.PAY,
                "PAYMENT",
                ValueType.MONEY,
                "PAYMENT_CHANNEL_DAY",
                true,
                "PAYMENT_SUCCESS_AMOUNT",
                "PAYMENT_FEE_AMOUNT");
        add(
                Owner.PAY,
                "PAYMENT",
                ValueType.COUNT,
                "REFUND_CHANNEL_DAY",
                false,
                "REFUND_SUCCESS_COUNT");
        add(
                Owner.PAY,
                "PAYMENT",
                ValueType.MONEY,
                "REFUND_CHANNEL_DAY",
                true,
                "REFUND_SUCCESS_AMOUNT");
        add(
                Owner.INVENTORY,
                "STOCK",
                ValueType.QUANTITY,
                "INVENTORY_MOVEMENT_DAY",
                true,
                "MATERIAL_ACTUAL_ISSUE_QTY",
                "MATERIAL_ACTUAL_REVERSAL_QTY",
                "MATERIAL_WASTE_QTY",
                "STOCK_RECEIPT_QTY",
                "STOCK_OUTBOUND_QTY",
                "STOCK_ADJUSTMENT_QTY");
        add(
                Owner.INVENTORY,
                "STOCK",
                ValueType.MONEY,
                "PROFIT_COST_DAY",
                true,
                "GOODS_SALES_COST_AMOUNT");
        add(
                Owner.INVENTORY,
                "STOCK",
                ValueType.MONEY,
                "MATERIAL_USAGE_DAY",
                true,
                "MATERIAL_ISSUE_COST_AMOUNT");
        add(
                Owner.EXPENSE,
                "EXPENSE",
                ValueType.MONEY,
                "PROFIT_COST_DAY",
                true,
                "OPERATING_EXPENSE_AMOUNT",
                "DELIVERY_FEE_COST_AMOUNT",
                "PLATFORM_COMMISSION_AMOUNT",
                "OPERATING_UNALLOCATED_LOSS_AMOUNT",
                "PAYMENT_ALLOCATED_FEE_COST_AMOUNT");
        for (String code : ReportProfitCalculator.OUTPUTS) {
            definitions.put(
                    code,
                    new Definition(
                            code,
                            new SourceKey(Owner.REPORT, "DERIVED"),
                            code.endsWith("_RATE") ? ValueType.QUANTITY : ValueType.MONEY,
                            Aggregation.DERIVED,
                            Set.of("PROFIT_COST_DAY"),
                            Set.of(),
                            true));
        }
        for (String code :
                Set.of(
                        "STOCK_ON_HAND_QTY",
                        "STOCK_OPENING_QTY",
                        "STOCK_CLOSING_QTY",
                        "STOCK_AVAILABLE_QTY",
                        "STOCK_RESERVED_QTY")) {
            definitions.put(
                    code,
                    new Definition(
                            code,
                            new SourceKey(Owner.INVENTORY, "STOCK"),
                            ValueType.QUANTITY,
                            Aggregation.LAST,
                            Set.of("INVENTORY_BALANCE_DAY"),
                            DIMENSIONS,
                            true));
        }
    }

    private void add(
            Owner owner,
            String family,
            ValueType type,
            String set,
            boolean negative,
            String... codes) {
        for (String code : codes)
            definitions.put(
                    code,
                    new Definition(
                            code,
                            new SourceKey(owner, family),
                            type,
                            Aggregation.SUM,
                            Set.of(set),
                            DIMENSIONS,
                            negative));
    }

    public Definition get(String code) {
        Definition d = definitions.get(code);
        if (d == null) throw new IllegalArgumentException("REPORT_METRIC_NOT_SUPPORTED");
        return d;
    }

    public Collection<Definition> definitions() {
        return List.copyOf(definitions.values());
    }

    /**
     * Implementation status, not a claim that the target deployment/source is currently available.
     */
    public boolean emptyGroupsAreKnown(String code) {
        return Set.of("DISH_ORDERED_QTY", "STOCK_RECEIPT_QTY", "STOCK_OUTBOUND_QTY").contains(code);
    }

    public String availability(String code) {
        var definition = get(code);
        if (definition.aggregation() == Aggregation.DERIVED)
            return "DERIVED_REQUIRES_COMPLETE_INPUTS";
        if (definition.source().equals(new SourceKey(Owner.FOOD, "KITCHEN")))
            return "PARTIAL_ORIGINAL_EVENT_ADAPTER";
        if (definition.source().owner() == Owner.PAY) return "PARTIAL_LEDGER_ADAPTER";
        if (Set.of("STOCK_ON_HAND_QTY", "STOCK_AVAILABLE_QTY", "STOCK_RESERVED_QTY").contains(code))
            return "LIVE_ONLY_ADAPTER";
        if (Set.of("STOCK_OPENING_QTY", "STOCK_CLOSING_QTY").contains(code))
            return "HISTORICAL_SOURCE_NOT_VERIFIED";
        if (Set.of(
                        "ORDER_CREATED_COUNT",
                        "ORDER_TRANSACTED_COUNT",
                        "ORDER_COMPLETED_COUNT",
                        "ORDER_ORIGINAL_DUE_AMOUNT",
                        "ORDER_EFFECTIVE_DUE_AMOUNT",
                        "ORDER_TRANSACTED_AMOUNT",
                        "RECEIVABLE_ADJUSTMENT_AMOUNT",
                        "ORDER_BATCH_COUNT",
                        "BATCH_ACCEPTED_EVENT_COUNT",
                        "DISH_ORDERED_QTY",
                        "STOCK_RECEIPT_QTY",
                        "STOCK_OUTBOUND_QTY")
                .contains(code)) return "READ_ADAPTER_IMPLEMENTED";
        return definition.source().owner() == Owner.EXPENSE
                ? "OWNER_NOT_CONFIGURED"
                : "ADAPTER_NOT_IMPLEMENTED";
    }

    public String hash() {
        return ReportEvidenceCodec.hash(
                definitions.values().stream()
                        .sorted(Comparator.comparing(Definition::code))
                        .map(
                                d ->
                                        List.of(
                                                d.code(),
                                                d.source(),
                                                d.type(),
                                                d.aggregation(),
                                                d.sets().stream().sorted().toList(),
                                                d.dimensions().stream().sorted().toList(),
                                                d.negativeAllowed()))
                        .toList());
    }

    public void validate(MetricFact fact, SourceKey source) {
        Definition d = get(fact.metricCode());
        if (!d.source().equals(source)
                || d.type() != fact.value().type()
                || !d.sets().contains(fact.aggregationSet())
                || !d.dimensions().containsAll(fact.dimensions().keySet()))
            throw new IllegalArgumentException("REPORT_FACT_CONTRACT_MISMATCH");
        var v = fact.value();
        if (!d.negativeAllowed()
                && ((v.integerValue() != null && v.integerValue() < 0)
                        || (v.quantityValue() != null && v.quantityValue().signum() < 0)
                        || (v.amountMinor() != null && v.amountMinor() < 0)))
            throw new IllegalArgumentException("REPORT_NEGATIVE_METRIC");
    }
}
