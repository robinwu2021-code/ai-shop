package ai.neargo.shop.trade.assembler;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import ai.neargo.shop.common.pricing.MoneyMinor;
import ai.neargo.shop.common.pricing.PricingPortion;
import ai.neargo.shop.common.pricing.PricingRuleFacts.ScopeStatus;
import ai.neargo.shop.common.pricing.PricingRuleFacts.PricingRole;
import ai.neargo.shop.common.pricing.PricingUnit;
import ai.neargo.shop.common.pricing.TaxEngine;
import ai.neargo.shop.trade.dto.PricingQuoteCommands.Allocation;
import ai.neargo.shop.trade.dto.PricingQuoteCommands.Amounts;
import ai.neargo.shop.trade.dto.PricingQuoteCommands.Context;
import ai.neargo.shop.trade.dto.PricingQuoteCommands.Kind;
import ai.neargo.shop.trade.dto.PricingQuoteCommands.SaveReq;
import org.springframework.stereotype.Component;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Closed typed snapshots and independent money reconciliation; never authenticates source facts or assets. */
@Component
public class PricingQuoteAssembler {
    private static final long MAX = 9007199254740991L;
    private final JsonMapper json = JsonMapper.builder(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(48)
                    .maxStringLength(1048576).build()).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    public void context(Context context) {
        Objects.requireNonNull(context);
        require("MAIN".equals(context.tenantNo()));
        identity(context.entityNo(), 32);
        identity(context.storeNo(), 32);
        identity(context.actorNo(), 32);
        if (context.subjectNo() != null) identity(context.subjectNo(), 32);
    }

    public void bounds(SaveReq request) {
        Objects.requireNonNull(request);
        Objects.requireNonNull(request.source());
        nonnegative(request.source().sourceVersion());
        if (request.source().scopeVersion() != null) nonnegative(request.source().scopeVersion());
        Objects.requireNonNull(request.industry());
        Objects.requireNonNull(request.phase());
        Objects.requireNonNull(request.facts());
        Objects.requireNonNull(request.tax());
        Objects.requireNonNull(request.intent());
        Objects.requireNonNull(request.intent().selection());
        Objects.requireNonNull(request.intent().funding());
        identity(request.channel(), 32);
        identity(request.storeRevisionNo(), 32);
        identity(request.templateRevisionNo(), 32);
        identity(request.engineSpecVersion(), 64);
        require(request.lifetimeSeconds() >= 1 && request.lifetimeSeconds() <= 300);
        require(request.lines() != null && !request.lines().isEmpty() && request.lines().size() <= 1024);
        require(request.allocations() != null && request.allocations().size() <= 4096);
        for (var allocation : request.allocations()) {
            require(allocation != null && allocation.funders() != null && allocation.funders().size() <= 64);
        }
    }

    public Prepared prepare(Context context, SaveReq request, String configSnapshot) {
        context(context);
        bounds(request);
        var tax = request.tax();
        require(context.tenantNo().equals(tax.tenantNo()) && context.entityNo().equals(tax.entityNo())
                && context.storeNo().equals(tax.storeNo()));
        require(request.source().sourceVersion() == request.facts().sourceVersion());
        String currency = request.facts().currency();
        var byPortion = new LinkedHashMap<PricingPortion, LineEvidence>();
        var sources = new ArrayList<PricingUnit>();
        var invoiceBases = new HashMap<String, Long>();
        var invoiceTax = new HashMap<String, Long>();
        long[] totals = new long[12];
        var bundleRoles = new HashMap<String, PricingRole>();
        for (var line : request.lines()) {
            Objects.requireNonNull(line.kind());
            Objects.requireNonNull(line.taxClass());
            if (line.parentSourceLineNo() != null) identity(line.parentSourceLineNo(), 64);
            var source = Objects.requireNonNull(line.source());
            var amounts = Objects.requireNonNull(line.amounts());
            require(source.baseAmount().currency().equals(currency));
            require(amounts.goodsMinor() == source.baseAmount().minor());
            checkAmounts(amounts, tax.priceTaxMode().name());
            long[] values = values(amounts);
            for (int i = 0; i < values.length; i++) totals[i] = Math.addExact(totals[i], values[i]);
            var portion = PricingPortion.from(source);
            var eligibility = Objects.requireNonNull(line.eligibility());
            require(portion.equals(eligibility.portion()) && eligibility.status() == ScopeStatus.OPEN);
            require(eligibility.promotionEligible()
                    || amounts.activityDiscountMinor() == 0 && amounts.couponDiscountMinor() == 0);
            if (eligibility.bundleGroup() != null && amounts.goodsMinor() > 0) {
                var role = bundleRoles.putIfAbsent(eligibility.bundleGroup(), eligibility.role());
                require(role == null || role == eligibility.role());
            }
            require(byPortion.put(portion, new LineEvidence(amounts)) == null);
            sources.add(source);
            nonnegative(line.invoiceBaseMinor());
            var invoiceBase = invoiceBases.putIfAbsent(source.taxInvoiceLineRef(), line.invoiceBaseMinor());
            require(invoiceBase == null || invoiceBase == line.invoiceBaseMinor());
            invoiceTax.merge(source.taxInvoiceLineRef(), amounts.taxMinor(), Math::addExact);
        }
        require(sources.equals(request.facts().units()));
        var taxLines = new ArrayList<TaxEngine.Line>();
        for (var line : request.lines()) {
            var source = line.source();
            var a = line.amounts();
            long base = tax.priceTaxMode().name().equals("EXCLUSIVE") ? a.netMinor() : a.receivableMinor();
            var adjusted = new PricingUnit(source.sourceLineNo(), source.componentRef(), source.goodsNo(),
                    source.skuNo(), source.priceRevision(), source.taxInvoiceLineRef(), source.quantityStart(),
                    source.quantityEnd(), source.unitPrice(), new MoneyMinor(currency, base));
            taxLines.add(new TaxEngine.Line(adjusted, line.taxClass(), tax.priceTaxMode(),
                    new MoneyMinor(currency, invoiceBases.get(source.taxInvoiceLineRef()))));
        }
        var computed = new TaxEngine().estimateConfigured(taxLines, tax,
                request.facts().sourceSnapshotHash(), request.facts().sourceVersion());
        for (var line : computed.lines()) {
            require(line.tax().minor() == invoiceTax.get(line.invoiceLineRef()));
        }
        checkAllocations(request, byPortion);
        var total = amounts(totals);
        for (long value : totals) require(value >= -MAX && value <= MAX);
        var funding = request.intent().funding();
        nonnegative(funding.storedPlannedMinor());
        nonnegative(funding.otherPlannedMinor());
        nonnegative(funding.cashDueMinor());
        require(funding.cashDueMinor() <= funding.otherPlannedMinor());
        long remaining = Math.subtractExact(Math.subtractExact(total.receivableMinor(),
                total.considerationOffsetMinor()),
                Math.addExact(funding.storedPlannedMinor(), funding.otherPlannedMinor()));
        nonnegative(remaining);
        Long change = null;
        if (funding.cashTenderMinor() != null) {
            nonnegative(funding.cashTenderMinor());
            require(funding.cashDueMinor() > 0 || funding.cashTenderMinor() == 0);
            change = funding.cashTenderMinor() - funding.cashDueMinor();
            nonnegative(change);
        } else require(funding.cashDueMinor() == 0);
        var factEvidence = new TreeMap<String, Object>();
        factEvidence.put("schemaVersion", 1);
        factEvidence.put("source", request.source());
        factEvidence.put("facts", request.facts());
        factEvidence.put("eligibility", request.lines().stream().map(line -> line.eligibility()).toList());
        if (request.orchestrationHash() != null) {
            require(request.orchestrationHash().matches("[0-9a-f]{64}"));
            factEvidence.put("orchestrationHash", request.orchestrationHash());
        }
        String facts = canonical(factEvidence);
        String config = canonical(json.readTree(configSnapshot));
        String plan = canonical(Map.of("schemaVersion", 1, "intent", request.intent(),
                "allocations", request.allocations()));
        String result = canonical(Map.of("schemaVersion", 1, "lines", request.lines(), "amounts", total,
                "tax", tax, "funding", funding, "remainingDueMinor", remaining));
        var economics = new TreeMap<String, Object>();
        economics.put("facts", json.readTree(facts));
        economics.put("config", json.readTree(config));
        economics.put("storeRevisionNo", request.storeRevisionNo());
        economics.put("templateRevisionNo", request.templateRevisionNo());
        economics.put("engineSpecVersion", request.engineSpecVersion());
        economics.put("industry", request.industry());
        economics.put("channel", request.channel());
        economics.put("phase", request.phase());
        economics.put("plan", json.readTree(plan));
        economics.put("result", json.readTree(result));
        String requestHash = hash(canonical(Map.of("source", request.source(), "intent", request.intent())));
        return new Prepared(hash(facts), requestHash,
                hash(canonical(economics)), facts, config, plan, result, total, remaining, change);
    }

    private void checkAllocations(SaveReq request, Map<PricingPortion, LineEvidence> lines) {
        var unique = new HashSet<String>();
        var pointDiscounts = new HashSet<String>();
        var pointConsideration = new HashSet<String>();
        long stored = 0;
        long other = 0;
        long cash = 0;
        for (Allocation allocation : request.allocations()) {
            identity(allocation.benefitRef(), 64);
            identity(allocation.owner(), 32);
            identity(allocation.ownerQuoteRef(), 128);
            identity(allocation.applicationRef(), 64);
            identity(allocation.ruleNo(), 64);
            identity(allocation.ruleVersion(), 64);
            identity(allocation.algorithmVersion(), 64);
            require(allocation.stepOrder() >= 0 && allocation.stepOrder() < 4096);
            nonnegative(allocation.basisMinor());
            nonnegative(allocation.amountMinor());
            require(allocation.amountMinor() <= allocation.basisMinor());
            require(allocation.payableRateBp() == null
                    || allocation.payableRateBp() >= 0 && allocation.payableRateBp() <= 10000);
            var line = lines.get(allocation.portion());
            require(line != null && allocation.kind() != null);
            require(unique.add(canonical(List.of(allocation.stepOrder(), allocation.applicationRef(),
                    allocation.portion()))));
            long funded = 0;
            var funders = new HashSet<String>();
            for (var funder : allocation.funders()) {
                identity(funder.funderRef(), 64);
                nonnegative(funder.amountMinor());
                require(funders.add(funder.funderRef()));
                funded = Math.addExact(funded, funder.amountMinor());
            }
            require(funded == allocation.amountMinor());
            line.allocations.merge(allocation.kind(), allocation.amountMinor(), Math::addExact);
            String asset = canonical(List.of(allocation.owner(), allocation.benefitRef()));
            if (allocation.kind() == Kind.POINTS_DISCOUNT) pointDiscounts.add(asset);
            if (allocation.kind() == Kind.POINTS_CONSIDERATION) pointConsideration.add(asset);
            if (allocation.kind() == Kind.STORED_PLAN) stored = Math.addExact(stored, allocation.amountMinor());
            if (allocation.kind() == Kind.OTHER_PLAN || allocation.kind() == Kind.CASH_PLAN) {
                other = Math.addExact(other, allocation.amountMinor());
            }
            if (allocation.kind() == Kind.CASH_PLAN) cash = Math.addExact(cash, allocation.amountMinor());
        }
        require(pointDiscounts.isEmpty() || pointConsideration.isEmpty());
        require(stored == request.intent().funding().storedPlannedMinor()
                && other == request.intent().funding().otherPlannedMinor()
                && cash == request.intent().funding().cashDueMinor());
        for (var line : lines.values()) {
            long[] expected = {line.amounts.activityDiscountMinor(), line.amounts.couponDiscountMinor(),
                    line.amounts.manualDiscountMinor(), line.amounts.pointsDiscountMinor(), line.amounts.waiverMinor(),
                    line.amounts.considerationOffsetMinor()};
            Kind[] kinds = {Kind.ACTIVITY_DISCOUNT, Kind.COUPON_DISCOUNT, Kind.MANUAL_DISCOUNT,
                    Kind.POINTS_DISCOUNT, Kind.WAIVER, Kind.POINTS_CONSIDERATION};
            for (int i = 0; i < kinds.length; i++) require(line.allocations.getOrDefault(kinds[i], 0L) == expected[i]);
            long planned = Math.addExact(line.allocations.getOrDefault(Kind.STORED_PLAN, 0L),
                    Math.addExact(line.allocations.getOrDefault(Kind.OTHER_PLAN, 0L),
                            line.allocations.getOrDefault(Kind.CASH_PLAN, 0L)));
            require(planned <= line.amounts.receivableMinor() - line.amounts.considerationOffsetMinor());
        }
    }

    private void checkAmounts(Amounts a, String mode) {
        long[] values = values(a);
        for (int i = 0; i < values.length; i++) {
            require(values[i] >= (i == 1 ? -MAX : 0) && values[i] <= MAX);
        }
        var base = BigInteger.valueOf(a.goodsMinor()).add(BigInteger.valueOf(a.priceAdjustmentMinor()))
                .subtract(BigInteger.valueOf(a.activityDiscountMinor()))
                .subtract(BigInteger.valueOf(a.couponDiscountMinor()))
                .subtract(BigInteger.valueOf(a.manualDiscountMinor()))
                .subtract(BigInteger.valueOf(a.pointsDiscountMinor()))
                .subtract(BigInteger.valueOf(a.waiverMinor())).add(BigInteger.valueOf(a.feeMinor()));
        require(base.signum() >= 0);
        require(base.longValueExact() == (mode.equals("EXCLUSIVE") ? a.netMinor() : a.receivableMinor()));
        require(Math.addExact(a.netMinor(), a.taxMinor()) == a.receivableMinor());
        require(a.considerationOffsetMinor() <= a.receivableMinor());
    }

    public String scopeHash(Context context) {
        return hash(canonical(Arrays.asList(context.tenantNo(), context.entityNo(),
                context.storeNo(), context.subjectNo())));
    }

    public String commandHash(SaveReq request) {
        bounds(request);
        if (request.orchestrationHash() == null) return hash(canonical(request));
        // Server-derived remaining lifetime decreases on retry; all immutable economic inputs still bind the key.
        var command = new TreeMap<String, Object>();
        command.put("source", request.source()); command.put("industry", request.industry());
        command.put("channel", request.channel()); command.put("phase", request.phase());
        command.put("storeRevisionNo", request.storeRevisionNo());
        command.put("templateRevisionNo", request.templateRevisionNo());
        command.put("engineSpecVersion", request.engineSpecVersion());
        command.put("facts", request.facts()); command.put("tax", request.tax());
        command.put("intent", request.intent()); command.put("lines", request.lines());
        command.put("allocations", request.allocations());
        command.put("orchestrationHash", request.orchestrationHash());
        return hash(canonical(command));
    }
    public String unitRef(PricingPortion portion) { return hash(canonical(portion)); }

    public String canonical(Object value) { return canonicalNode(json.valueToTree(value)); }

    private String canonicalNode(JsonNode node) {
        String result = json.writeValueAsString(normalize(node));
        require(result.getBytes(StandardCharsets.UTF_8).length <= 8388608);
        return result;
    }

    private Object normalize(JsonNode node) {
        if (node.isObject()) {
            var result = new TreeMap<String, Object>();
            node.properties().forEach(entry -> result.put(entry.getKey(), normalize(entry.getValue())));
            return result;
        }
        if (node.isArray()) {
            var result = new ArrayList<Object>();
            node.forEach(item -> result.add(normalize(item)));
            return result;
        }
        if (node.isBigDecimal()) return node.decimalValue().stripTrailingZeros();
        return node;
    }

    public static void identity(String value, int maximum) {
        require(value != null && !value.isBlank() && value.length() <= maximum
                && value.codePoints().noneMatch(c -> Character.isWhitespace(c)
                        || Character.isSpaceChar(c) || Character.isISOControl(c)));
    }

    private static void nonnegative(long value) { require(value >= 0 && value <= MAX); }
    private static void require(boolean valid) {
        if (!valid) throw new IllegalArgumentException("Invalid or non-conserving quote evidence");
    }
    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private long[] values(Amounts a) {
        return new long[] {a.goodsMinor(), a.priceAdjustmentMinor(), a.activityDiscountMinor(), a.couponDiscountMinor(),
                a.manualDiscountMinor(), a.pointsDiscountMinor(), a.waiverMinor(), a.feeMinor(), a.netMinor(),
                a.taxMinor(), a.receivableMinor(), a.considerationOffsetMinor()};
    }
    private Amounts amounts(long[] v) {
        return new Amounts(v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7], v[8], v[9], v[10], v[11]);
    }
    private static final class LineEvidence {
        private final Amounts amounts;
        private final Map<Kind, Long> allocations = new EnumMap<>(Kind.class);
        private LineEvidence(Amounts amounts) { this.amounts = amounts; }
    }
    public record Prepared(String factsHash, String requestHash, String economicsHash, String factsSnapshot,
            String configSnapshot, String planSnapshot, String resultSnapshot, Amounts amounts,
            long remainingDueMinor, Long cashChangeMinor) { }
}
