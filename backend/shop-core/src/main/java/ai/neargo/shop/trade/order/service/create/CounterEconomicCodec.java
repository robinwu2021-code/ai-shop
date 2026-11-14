package ai.neargo.shop.trade.order.service.create;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.marketing.CampaignPort;
import ai.neargo.shop.spi.marketing.CampaignPort.GoodsLine;
import ai.neargo.shop.spi.marketing.CampaignPort.MerchantAmount;
import ai.neargo.shop.spi.marketing.CouponPort;
import ai.neargo.shop.spi.marketing.CounterCampaignContract;
import ai.neargo.shop.spi.trade.CheckoutPort.CheckoutLineReq;
import ai.neargo.shop.spi.trade.CheckoutPort.PromotionOption;
import ai.neargo.shop.spi.trade.CheckoutPort.QuoteLine;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort.CustomerParty;
import ai.neargo.shop.trade.service.OrderService.CreateOrderCommand;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** Strict frozen trade evidence is decoded without querying owners or rewriting old quote/plan bytes. */
public final class CounterEconomicCodec {
    private final ObjectMapper json;
    private final ObjectReader strict;

    /** Only a trusted original persisted quote may select the historical zero pricing policy. */
    public enum CounterPolicy {
        CURRENT_FULL_CUT,
        FROZEN_M2A_ZERO
    }

    /** Keep application serialization unchanged; strict read features live on a private immutable reader. */
    public CounterEconomicCodec(ObjectMapper json) {
        this.json = Objects.requireNonNull(json, "counter codec mapper");
        this.strict = json.reader().with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY,
                        DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    /** Recognize an exact new envelope or an old bare zero quote, never a mixed or repaired shape. */
    public CounterQuoteSnapshot readQuoteSnapshot(String pricingJson) {
        try {
            JsonNode root = tree(pricingJson);
            if (root.has("schema") || root.has("economic") || root.has("promotions")) {
                recordShape(root, CounterQuoteSnapshot.class);
                check(root.get("schema").intValue() == 1, "only schema1 quote envelopes are persisted");
                check(root.get("economic").has("counterActivitySeal"), "new quote seal missing");
                CounterQuoteSnapshot snapshot = json.treeToValue(root, CounterQuoteSnapshot.class);
                requireSnapshot(snapshot);
                return snapshot;
            }
            recordShape(root, CheckoutEconomicSnapshot.class);
            check(!root.has("counterActivitySeal"), "bare quote cannot carry a seal");
            CheckoutEconomicSnapshot economic = json.treeToValue(root, CheckoutEconomicSnapshot.class);
            requireLegacyMoney(economic);
            return new CounterQuoteSnapshot(0, economic, oldPromotions(economic));
        } catch (RuntimeException failure) {
            throw conflict();
        }
    }

    /** Counter consumers unwrap quote display while retaining the exact economic authority. */
    public CheckoutEconomicSnapshot readQuote(String pricingJson) {
        return readQuoteSnapshot(pricingJson).economic();
    }

    /** Plans keep their existing bare economic member; quote envelopes have no place in creation authority. */
    public CounterOrderPlan readPlan(String counterPlanJson) {
        try {
            JsonNode root = tree(counterPlanJson);
            recordShape(root, CounterOrderPlan.class);
            CounterOrderPlan plan = json.treeToValue(root, CounterOrderPlan.class);
            requirePlan(plan);
            return plan;
        } catch (RuntimeException failure) {
            throw conflict();
        }
    }

    /** Serialize schema0 only when explicitly requested, without upgrading or inventing old display options. */
    public String writeQuote(CounterQuoteSnapshot snapshot) {
        try {
            requireSnapshot(snapshot);
            return json.writeValueAsString(snapshot.schema() == 0 ? snapshot.economic() : snapshot);
        } catch (RuntimeException failure) {
            throw conflict();
        }
    }

    /** Preserve the original plan's application serializer and economic-only shape. */
    public String writePlan(CounterOrderPlan plan) {
        try {
            requirePlan(plan);
            return json.writeValueAsString(plan);
        } catch (RuntimeException failure) {
            throw conflict();
        }
    }

    /** Keep the existing full typed plan hash algorithm, excluding quote-only display. */
    public String planHash(CounterOrderPlan plan) {
        try {
            requirePlan(plan);
            return CheckoutDigests.hash(json, plan);
        } catch (RuntimeException failure) {
            throw conflict();
        }
    }

    /** Zero classification grants no authority; callers still prove original operation, consent and intent. */
    public boolean legacyZero(CheckoutEconomicSnapshot economic, CreateOrderCommand command) {
        try {
            requireLegacyMoney(economic);
            requireCommand(command, economic);
            check(command.activityChoices() != null && command.activityChoices().isEmpty(),
                    "old choices must be empty");
            return true;
        } catch (RuntimeException failure) {
            return false;
        }
    }

    /** Reconstruct the actual existing counter tuple from original typed party, request lines and frozen evidence. */
    public void requireQuoteHash(CustomerParty party, List<CheckoutLineReq> originalLines,
            CheckoutEconomicSnapshot economic, String persistedQuoteHash) {
        try {
            check(party != null && economic != null && originalLines != null, "original counter hash context required");
            new CustomerParty(party.kind(), party.userNo(), party.guestNo(), party.identityRevision());
            check(Objects.equals(party.userNo(), economic.buyerNo()), "counter buyer mismatch");
            requireLines(economic, originalLines);
            var evidence = economic.evidence();
            requireEvidence(evidence);
            if (economic.counterActivitySeal() != null) {
                check(party.equals(economic.counterActivitySeal().party()), "counter sealed party mismatch");
            }
            var applied = economic.activity().applied().stream().map(a -> new CampaignPort.AppliedActivity(
                    a.activityNo(), a.merchantNo(), a.amountMinor(), a.qty(), a.platformMinor(), a.enrollmentNo(),
                    null, a.storeNo())).toList();
            var activity = new CampaignPort.Discount(
                    economic.activity().total(), economic.activity().shares(), applied);
            var coupon = new CouponPort.Allocation(economic.coupon().totalDiscount(), economic.coupon().byMerchant(),
                    economic.coupon().shares(), null);
            var money = new CheckoutEconomicSnapshot(null, evidence, economic.buyerNo(), economic.entityNo(),
                    economic.storeNo(), economic.currency(), economic.payMode(), economic.fulfillment(),
                    economic.fulfillmentAuthority(), economic.lines(), economic.goodsMinor(), economic.freightMinor(),
                    economic.discountMinor(), economic.dueMinor(), economic.platformMinor(), economic.merchantMinor(),
                    activity, coupon, economic.counterActivitySeal());
            String expected = CheckoutDigests.hash(json, Arrays.asList("COUNTER_MANUAL_QUOTE_V1", party,
                    Arrays.asList(evidence.tenantNo(), economic.entityNo(), economic.storeNo(),
                            evidence.scopeRevision(), evidence.scopeHash(), originalLines,
                            evidence.catalogVersionNo(), evidence.catalogRevision(),
                            evidence.catalogHash()), money));
            check(expected.equals(economic.quoteHash()) && expected.equals(persistedQuoteHash),
                    "counter quote hash mismatch");
        } catch (RuntimeException failure) {
            throw conflict();
        }
    }

    private void requireSnapshot(CounterQuoteSnapshot snapshot) {
        check(snapshot != null, "counter quote snapshot required");
        if (snapshot.schema() == 0) {
            requireLegacyMoney(snapshot.economic());
            check(oldPromotions(snapshot.economic()).equals(snapshot.promotions()), "old quote display changed");
            return;
        }
        check(snapshot.schema() == 1, "unsupported counter quote schema");
        requireCurrentMoney(snapshot.economic());
        List<PromotionOption> options = snapshot.promotions();
        check(options != null && !options.isEmpty(), "counter quote options required");
        var ids = new HashSet<String>();
        for (int index = 0; index < options.size(); index++) {
            PromotionOption option = options.get(index);
            check(option != null && reference(option.promotionNo()) && ids.add(option.promotionNo())
                    && "ACTIVITY".equals(option.kind()) && option.eligible() && option.reasonCode() == null,
                    "invalid counter quote option");
            if (CampaignPort.CHOICE_NONE.equals(option.promotionNo())) {
                check(index == options.size() - 1 && option.title() == null, "NONE must be the final untitled option");
            }
        }
        check(CampaignPort.CHOICE_NONE.equals(options.getLast().promotionNo()), "counter NONE option missing");
        for (var applied : snapshot.economic().activity().applied()) {
            check(ids.contains(applied.activityNo()), "selected counter option missing");
        }
    }

    private void requirePlan(CounterOrderPlan plan) {
        check(plan != null && plan.party() != null && plan.command() != null && plan.economic() != null
                && text(plan.orderNo()) && text(plan.subOrderNo()) && text(plan.operationNo()) && text(plan.intentKey())
                && text(plan.requestHash()) && text(plan.stockAuthority()) && text(plan.manifestHash())
                && (plan.lifecycleVersion() == 1 || plan.lifecycleVersion() == 2) && plan.intentRevision() > 0
                && plan.stockNeeds() != null && !plan.stockNeeds().isEmpty()
                && plan.stockNeeds().stream().allMatch(n -> n != null && reference(n.skuNo()) && n.qty() > 0),
                "counter plan facts required");
        requireCommand(plan.command(), plan.economic());
        if (plan.economic().counterActivitySeal() == null) {
            check(legacyZero(plan.economic(), plan.command()), "invalid old counter plan economics");
        } else {
            requireCurrentMoney(plan.economic());
            check(plan.party().equals(plan.economic().counterActivitySeal().party()), "plan sealed party mismatch");
            check(plan.command().activityChoices() != null && plan.command().activityChoices()
                    .equals(plan.economic().counterActivitySeal().requestedChoices()),
                    "plan original choices mismatch");
        }
        requireQuoteHash(plan.party(), originalLines(plan.economic()), plan.economic(), plan.economic().quoteHash());
    }

    private void requireCurrentMoney(CheckoutEconomicSnapshot economic) {
        check(economic != null && economic.counterActivitySeal() != null, "counter economic seal required");
        requireLane(economic);
        CounterCampaignContract.requireSeal(json, economic.counterActivitySeal());
        check(economic.discountMinor() >= 0 && economic.discountMinor() < economic.goodsMinor()
                && economic.freightMinor() == 0 && economic.platformMinor() == 0
                && economic.merchantMinor() == economic.discountMinor()
                && Math.subtractExact(economic.goodsMinor(), economic.discountMinor()) == economic.dueMinor()
                && economic.activity() != null && economic.activity().total() == economic.discountMinor()
                && CouponPort.Allocation.none().equals(economic.coupon()), "invalid counter funding");
        check(economic.activity().applied() != null
                && economic.activity().applied().stream().allMatch(a -> a != null && a.name() == null),
                "counter economic activity names must be absent");
        List<MerchantAmount> groups = groups(economic);
        CounterCampaignContract.requireSelection(json, economic.counterActivitySeal(),
                economic.counterActivitySeal().party(), groups, economic.counterActivitySeal().requestedChoices(),
                economic.activity());
        check(Objects.equals(economic.counterActivitySeal().party().userNo(), economic.buyerNo()),
                "counter economic buyer mismatch");
        requireCommandEvidence(economic, economic.counterActivitySeal().requestedChoices());
        requireQuoteHash(economic.counterActivitySeal().party(), originalLines(economic),
                economic, economic.quoteHash());
    }

    private void requireLegacyMoney(CheckoutEconomicSnapshot economic) {
        check(economic != null && economic.counterActivitySeal() == null, "old counter seal must be absent");
        requireLane(economic);
        check(economic.discountMinor() == 0 && economic.platformMinor() == 0 && economic.merchantMinor() == 0
                && economic.freightMinor() == 0 && economic.goodsMinor() == economic.dueMinor()
                && CampaignPort.Discount.none().equals(economic.activity())
                && CouponPort.Allocation.none().equals(economic.coupon()), "invalid old counter zero economics");
        requireCommandEvidence(economic, Map.of());
    }

    private static void requireLane(CheckoutEconomicSnapshot economic) {
        requireEvidence(economic.evidence());
        check(economic.quoteHash() != null && economic.quoteHash().matches("[0-9a-f]{64}")
                && reference(economic.entityNo()) && reference(economic.storeNo()) && "CNY".equals(economic.currency())
                && "OFFLINE".equals(economic.payMode()) && "STORE_VERIFY".equals(economic.fulfillment())
                && "SERVICE_EXECUTION".equals(economic.fulfillmentAuthority())
                && economic.goodsMinor() > 0 && economic.dueMinor() > 0, "unsupported counter economic lane");
        requireLines(economic, originalLines(economic));
    }

    private static void requireEvidence(CheckoutEconomicSnapshot.Evidence evidence) {
        check(evidence != null && "MAIN".equals(evidence.tenantNo()) && evidence.scopeRevision() > 0
                && evidence.scopeHash() != null && !evidence.scopeHash().isBlank()
                && evidence.commandHash() != null && evidence.commandHash().matches("[0-9a-f]{64}")
                && reference(evidence.catalogVersionNo()) && evidence.catalogRevision() > 0
                && evidence.catalogHash() != null && !evidence.catalogHash().isBlank(), "invalid counter evidence");
    }

    private void requireCommandEvidence(CheckoutEconomicSnapshot economic, Map<String, String> choices) {
        String expected = CheckoutDigests.hash(json, Arrays.asList(originalLines(economic), null,
                new TreeMap<>(choices), economic.payMode()));
        check(expected.equals(economic.evidence().commandHash()), "counter original command evidence mismatch");
    }

    private static void requireCommand(CreateOrderCommand command, CheckoutEconomicSnapshot economic) {
        check(command != null && command.items() != null && !command.items().isEmpty()
                && command.items().size() == economic.lines().size()
                && command.couponNo() == null && (command.usePoints() == null || command.usePoints() == 0)
                && command.groupNo() == null && !command.openGroup() && command.appointmentAt() == null
                && command.appointmentSlotNo() == null && "STORE_VERIFY".equals(command.fulfillment())
                && "OFFLINE".equals(command.payMode()) && command.pickupNo() == null && command.addressId() == null,
                "unsupported counter original command");
        for (int index = 0; index < command.items().size(); index++) {
            var item = command.items().get(index);
            QuoteLine line = economic.lines().get(index);
            check(item != null && line.skuNo().equals(item.skuNo()) && line.quantity() == item.qty()
                    && (item.goodsNo() == null || line.goodsNo().equals(item.goodsNo())),
                    "counter command line mismatch");
        }
    }

    private static void requireLines(CheckoutEconomicSnapshot economic, List<CheckoutLineReq> originalLines) {
        check(economic.lines() != null && !economic.lines().isEmpty() && originalLines != null
                && originalLines.size() == economic.lines().size(), "counter lines required");
        Set<String> refs = new HashSet<>();
        long goods = 0;
        long discount = 0;
        long due = 0;
        for (int index = 0; index < economic.lines().size(); index++) {
            QuoteLine line = economic.lines().get(index);
            CheckoutLineReq original = originalLines.get(index);
            check(line != null && original != null && reference(line.clientLineRef()) && refs.add(line.clientLineRef())
                    && reference(line.skuNo()) && reference(line.goodsNo())
                    && line.quantity() > 0 && line.quantity() <= 999
                    && "COUNT".equals(line.uom()) && line.unitPriceMinor() >= 0 && line.grossMinor() >= 0
                    && line.discountMinor() >= 0 && line.dueMinor() >= 0
                    && Math.multiplyExact(line.unitPriceMinor(), line.quantity()) == line.grossMinor()
                    && Math.subtractExact(line.grossMinor(), line.discountMinor()) == line.dueMinor()
                    && line.productVersionHash() != null && !line.productVersionHash().isBlank()
                    && original.clientLineRef().equals(line.clientLineRef()) && original.skuNo().equals(line.skuNo())
                    && original.quantity() == line.quantity(), "invalid counter original line");
            goods = Math.addExact(goods, line.grossMinor());
            discount = Math.addExact(discount, line.discountMinor());
            due = Math.addExact(due, line.dueMinor());
        }
        check(goods == economic.goodsMinor() && discount == economic.discountMinor() && due == economic.dueMinor(),
                "counter line aggregate mismatch");
    }

    private static List<MerchantAmount> groups(CheckoutEconomicSnapshot economic) {
        int quantity = 0;
        for (QuoteLine line : economic.lines()) {
            quantity = Math.addExact(quantity, line.quantity());
        }
        return CounterCampaignContract.canonicalGroups(List.of(new MerchantAmount(economic.entityNo(),
                economic.goodsMinor(), quantity, economic.storeNo(), economic.lines().stream()
                        .map(l -> new GoodsLine(l.goodsNo(), l.grossMinor(), l.quantity())).toList(), false)));
    }

    private static List<CheckoutLineReq> originalLines(CheckoutEconomicSnapshot economic) {
        check(economic != null && economic.lines() != null && economic.lines().stream().noneMatch(Objects::isNull),
                "counter original price lines required");
        return economic.lines().stream()
                .map(l -> new CheckoutLineReq(l.clientLineRef(), l.skuNo(), l.quantity())).toList();
    }

    private static List<PromotionOption> oldPromotions(CheckoutEconomicSnapshot economic) {
        return economic.activity().applied().stream().map(a -> new PromotionOption(
                a.activityNo(), "ACTIVITY", a.name(), true, null)).toList();
    }

    private JsonNode tree(String value) {
        check(value != null && !value.isBlank(), "counter stored JSON required");
        JsonNode node = strict.readTree(value);
        check(node != null && node.isObject(), "counter stored root must be object");
        return node;
    }

    private static void recordShape(JsonNode node, Class<?> type) {
        check(node != null && node.isObject() && type.isRecord(), "exact counter record required");
        var expected = new HashSet<String>();
        for (var component : type.getRecordComponents()) {
            expected.add(component.getName());
            boolean optionalSeal = type == CheckoutEconomicSnapshot.class
                    && "counterActivitySeal".equals(component.getName());
            if (optionalSeal && !node.has(component.getName())) {
                continue;
            }
            check(node.has(component.getName()), "missing counter record property");
            JsonNode value = node.get(component.getName());
            if (optionalSeal) {
                check(!value.isNull(), "explicit null counter seal is invalid");
            }
            valueShape(value, component.getGenericType());
        }
        check(expected.containsAll(node.propertyNames()), "unknown counter record property");
    }

    private static void valueShape(JsonNode node, Type type) {
        check(node != null, "counter property required");
        if (type instanceof ParameterizedType parameterized) {
            check(!node.isNull(), "counter collections cannot be null");
            Type raw = parameterized.getRawType();
            if (raw == List.class) {
                check(node.isArray(), "counter array required");
                for (JsonNode item : node) {
                    check(!item.isNull(), "null counter array element");
                    valueShape(item, parameterized.getActualTypeArguments()[0]);
                }
            } else if (raw == Map.class) {
                check(node.isObject(), "counter map required");
                for (var property : node.properties()) {
                    check(!property.getValue().isNull(), "null counter map value");
                    valueShape(property.getValue(), parameterized.getActualTypeArguments()[1]);
                }
            } else {
                throw new IllegalArgumentException("unsupported counter generic type");
            }
            return;
        }
        check(type instanceof Class<?>, "counter concrete type required");
        Class<?> valueType = (Class<?>) type;
        if (node.isNull()) {
            check(!valueType.isPrimitive(), "null counter primitive");
        } else if (valueType == String.class) {
            check(node.isString(), "counter string required without coercion");
        } else if (valueType == long.class || valueType == Long.class) {
            check(node.isIntegralNumber() && node.canConvertToLong(), "counter long required without coercion");
        } else if (valueType == int.class || valueType == Integer.class) {
            check(node.isIntegralNumber() && node.canConvertToInt(), "counter integer required without coercion");
        } else if (valueType == boolean.class || valueType == Boolean.class) {
            check(node.isBoolean(), "counter boolean required without coercion");
        } else if (valueType.isRecord()) {
            recordShape(node, valueType);
        } else {
            throw new IllegalArgumentException("unsupported counter record type");
        }
    }

    private static boolean text(String value) {
        return value != null && !value.isBlank();
    }

    private static boolean reference(String value) {
        return value != null && !value.isBlank() && value.length() <= 64 && value.equals(value.strip());
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    private static BizException conflict() {
        return BizException.of(ErrorCode.CONFLICT);
    }
}
