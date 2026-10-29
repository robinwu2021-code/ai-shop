package ai.neargo.shop.spi.marketing;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.marketing.CampaignPort.CounterActivitySeal;
import ai.neargo.shop.spi.marketing.CampaignPort.CounterChoice;
import ai.neargo.shop.spi.marketing.CampaignPort.CounterRule;
import ai.neargo.shop.spi.marketing.CampaignPort.Discount;
import ai.neargo.shop.spi.marketing.CampaignPort.GoodsLine;
import ai.neargo.shop.spi.marketing.CampaignPort.MerchantAmount;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort.CustomerParty;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** One pure protocol lets owners and trade verify frozen facts without private domain dependencies or owner IO. */
public final class CounterCampaignContract {
    private static final String POLICY = "LEGACY_FULL_CUT_V1";
    private static final Comparator<CounterRule> RULE_ORDER = Comparator.comparing(CounterRule::entityNo)
            .thenComparing(CounterRule::storeNo, Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(CounterRule::activityNo);

    private CounterCampaignContract() {}

    /** Aggregate exact goods facts once so owner and consumer seal the same original priced group. */
    public static List<MerchantAmount> canonicalGroups(List<MerchantAmount> groups) {
        try {
            require(groups != null && groups.size() == 1, "one counter group required");
            MerchantAmount group = groups.getFirst();
            require(group != null, "counter group required");
            reference(group.merchantNo());
            reference(group.storeNo());
            require(group.goodsAmount() > 0 && group.goodsQty() > 0 && !group.pointsRewardsEnabled(),
                    "unsupported counter gross or rewards");
            require(group.lines() != null && !group.lines().isEmpty(), "counter goods required");
            var goods = new TreeMap<String, GoodsLine>();
            long amount = 0;
            int qty = 0;
            for (GoodsLine line : group.lines()) {
                require(line != null, "counter goods line required");
                reference(line.goodsNo());
                require(line.amount() >= 0 && line.qty() > 0, "invalid counter goods amount or count");
                amount = Math.addExact(amount, line.amount());
                qty = Math.addExact(qty, line.qty());
                GoodsLine prior = goods.get(line.goodsNo());
                goods.put(line.goodsNo(), prior == null ? new GoodsLine(line.goodsNo(), line.amount(), line.qty())
                        : new GoodsLine(line.goodsNo(), Math.addExact(prior.amount(), line.amount()),
                                Math.addExact(prior.qty(), line.qty())));
            }
            require(amount == group.goodsAmount() && qty == group.goodsQty(), "counter goods totals mismatch");
            return List.of(new MerchantAmount(group.merchantNo(), amount, qty, group.storeNo(),
                    List.copyOf(goods.values()), false));
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("counter goods overflow", overflow);
        }
    }

    /** Retain accepted exact aliases while rejecting foreign consent before activity lookup. */
    public static Map<String, String> normalizeChoices(List<MerchantAmount> groups, Map<String, String> choices) {
        MerchantAmount group = canonicalGroups(groups).getFirst();
        if (choices == null) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        var normalized = new TreeMap<String, String>();
        for (var entry : choices.entrySet()) {
            if (!validReference(entry.getKey()) || !validReference(entry.getValue())) {
                throw BizException.of(ErrorCode.BAD_REQUEST);
            }
        }
        for (var entry : choices.entrySet()) {
            if (!entry.getKey().equals(group.merchantNo()) && !entry.getKey().equals(group.storeNo())) {
                throw BizException.of(ErrorCode.ACTIVITY_CHOICE_UNAVAILABLE);
            }
            normalized.put(entry.getKey(), entry.getValue());
        }
        String entity = normalized.get(group.merchantNo());
        String store = normalized.get(group.storeNo());
        if (entity != null && store != null && !entity.equals(store)) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        return Collections.unmodifiableMap(normalized);
    }

    /** Resolve against actual groups even when the owner has no candidates, including a valid NONE choice. */
    public static List<CounterChoice> resolveChoices(List<MerchantAmount> groups, Map<String, String> choices) {
        MerchantAmount group = canonicalGroups(groups).getFirst();
        Map<String, String> requested = normalizeChoices(groups, choices);
        String value = requested.containsKey(group.storeNo()) ? requested.get(group.storeNo())
                : requested.get(group.merchantNo());
        String mode = value == null ? "AUTO" : CampaignPort.CHOICE_NONE.equals(value) ? "NONE" : "EXPLICIT";
        return List.of(new CounterChoice(group.merchantNo(), group.storeNo(), mode,
                "EXPLICIT".equals(mode) ? value : null));
    }

    /** Seal only owner-produced scoped facts; this pure operation cannot establish database scope membership. */
    public static CounterActivitySeal seal(ObjectMapper json, CustomerParty party, List<MerchantAmount> groups,
            Map<String, String> choices, List<CounterRule> selectedRules) {
        try {
            var canonical = canonicalGroups(groups);
            var requested = normalizeChoices(canonical, choices);
            require(selectedRules != null && selectedRules.stream().noneMatch(Objects::isNull),
                    "counter rules required");
            var rules = new ArrayList<>(selectedRules);
            rules.sort(RULE_ORDER);
            var unsigned = new CounterActivitySeal(1, POLICY, party, canonical, requested,
                    resolveChoices(canonical, requested), rules, null);
            return new CounterActivitySeal(1, POLICY, party, canonical, requested, unsigned.resolvedChoices(), rules,
                    sealDigest(json, unsigned));
        } catch (BizException | NullPointerException failure) {
            throw new IllegalArgumentException("invalid counter seal construction", failure);
        }
    }

    /** Hash the declared positional protocol with explicit nullable positions and sorted map entries. */
    public static String sealDigest(ObjectMapper json, CounterActivitySeal seal) {
        shape(seal);
        require(json != null, "counter mapper required");
        var groupTuples = seal.groups().stream().map(g -> Arrays.asList(g.merchantNo(), g.goodsAmount(), g.goodsQty(),
                g.storeNo(), g.lines().stream().map(l -> Arrays.asList(l.goodsNo(), l.amount(), l.qty())).toList(),
                g.pointsRewardsEnabled())).toList();
        var choiceTuples = seal.resolvedChoices().stream().map(c -> Arrays.asList(
                c.entityNo(), c.storeNo(), c.mode(), c.activityNo())).toList();
        var ruleTuples = seal.selectedRules().stream().map(r -> Arrays.asList(r.tenantNo(), r.activityNo(),
                r.entityNo(), r.storeNo(), r.ruleVersion(), r.type(), r.status(), r.deleted(), r.thresholdMinor(),
                r.discountMinor(), r.startAt(), r.endAt(), r.goodsNos(), r.appliedMinor(), r.funder(),
                r.platformMinor())).toList();
        CustomerParty party = seal.party();
        Object tuple = Arrays.asList("COUNTER_ACTIVITY_SEAL_V1", seal.schema(), seal.policy(),
                Arrays.asList(party.kind(), party.userNo(), party.guestNo(), party.identityRevision()),
                groupTuples, seal.requestedChoices(), choiceTuples, ruleTuples);
        try {
            String encoded = json.writer().with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                    .writeValueAsString(tuple);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(encoded.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("counter seal serialization failed", failure);
        }
    }

    /** Stored evidence must already have canonical lists and the exact digest; reads never repair it. */
    public static void requireSeal(ObjectMapper json, CounterActivitySeal seal) {
        require(seal != null && seal.digest() != null && seal.digest().matches("[0-9a-f]{64}"),
                "counter digest required");
        require(seal.digest().equals(sealDigest(json, seal)), "counter digest mismatch");
    }

    /** Bind the frozen selected allocation to original consent without reevaluating present owner eligibility. */
    public static void requireSelection(ObjectMapper json, CounterActivitySeal seal, CustomerParty originalParty,
            List<MerchantAmount> originalGroups, Map<String, String> originalChoices, Discount originalSelected) {
        try {
            requireSeal(json, seal);
            require(seal.party().equals(originalParty), "counter original party mismatch");
            require(seal.groups().equals(canonicalGroups(originalGroups)), "counter original goods mismatch");
            require(seal.requestedChoices().equals(normalizeChoices(originalGroups, originalChoices)),
                    "counter original choices mismatch");
            require(seal.resolvedChoices().equals(resolveChoices(originalGroups, originalChoices)),
                    "counter original resolution mismatch");
            require(originalSelected != null && originalSelected.shares() != null && originalSelected.applied() != null,
                    "counter selected allocation required");
            if (seal.selectedRules().isEmpty()) {
                require(Discount.none().equals(originalSelected), "counter zero selection mismatch");
                return;
            }
            CounterRule rule = seal.selectedRules().getFirst();
            MerchantAmount group = seal.groups().getFirst();
            require(originalSelected.total() == rule.appliedMinor() && originalSelected.shares().size() == 1
                    && originalSelected.applied().size() == 1, "counter selected allocation count mismatch");
            var share = originalSelected.shares().getFirst();
            var applied = originalSelected.applied().getFirst();
            require(share != null && applied != null && group.merchantNo().equals(share.merchantNo())
                    && group.storeNo().equals(share.storeNo()) && share.amount() == rule.appliedMinor()
                    && rule.activityNo().equals(applied.activityNo()) && group.merchantNo().equals(applied.merchantNo())
                    && group.storeNo().equals(applied.storeNo()) && applied.amountMinor() == rule.appliedMinor()
                    && applied.qty() == 1 && applied.platformMinor() == 0 && applied.enrollmentNo() == null,
                    "counter frozen canonical allocation mismatch");
        } catch (BizException | NullPointerException failure) {
            throw new IllegalArgumentException("invalid counter original selection", failure);
        }
    }

    private static void shape(CounterActivitySeal seal) {
        try {
            require(seal != null && seal.schema() == 1 && POLICY.equals(seal.policy()) && seal.party() != null,
                    "unsupported counter seal");
            CustomerParty party = seal.party();
            new CustomerParty(party.kind(), party.userNo(), party.guestNo(), party.identityRevision());
            require(seal.groups().equals(canonicalGroups(seal.groups())), "noncanonical counter groups");
            require(seal.requestedChoices().equals(normalizeChoices(seal.groups(), seal.requestedChoices())),
                    "noncanonical counter choices");
            require(seal.resolvedChoices().equals(resolveChoices(seal.groups(), seal.requestedChoices())),
                    "counter choice resolution mismatch");
            require(seal.selectedRules() != null && seal.selectedRules().size() <= 1,
                    "one selected counter rule allowed");
            CounterChoice choice = seal.resolvedChoices().getFirst();
            if (seal.selectedRules().isEmpty()) {
                require(!"EXPLICIT".equals(choice.mode()), "explicit counter rule missing");
                return;
            }
            require(!"NONE".equals(choice.mode()), "NONE cannot select counter rule");
            CounterRule rule = seal.selectedRules().getFirst();
            require(rule != null, "counter rule missing");
            reference(rule.activityNo());
            require(!CampaignPort.CHOICE_NONE.equals(rule.activityNo()), "reserved activity reference");
            rawScope(rule.tenantNo(), 32, false);
            rawScope(rule.entityNo(), 64, false);
            rawScope(rule.storeNo(), 64, true);
            require(!"EXPLICIT".equals(choice.mode()) || rule.activityNo().equals(choice.activityNo()),
                    "explicit counter identity mismatch");
            MerchantAmount group = seal.groups().getFirst();
            require(rule.ruleVersion() >= 0 && "FULL_CUT".equals(rule.type()) && "RUNNING".equals(rule.status())
                    && rule.deleted() == 0 && rule.goodsNos() != null && rule.goodsNos().isEmpty()
                    && "MERCHANT".equals(rule.funder()) && rule.platformMinor() == 0 && rule.discountMinor() != null
                    && rule.discountMinor() > 0 && rule.appliedMinor() > 0 && rule.appliedMinor() < group.goodsAmount()
                    && rule.appliedMinor() == Math.min(rule.discountMinor(), group.goodsAmount())
                    && group.goodsAmount() >= (rule.thresholdMinor() == null ? 0 : rule.thresholdMinor())
                    && rule.startAt() != null && rule.endAt() != null && rule.startAt() <= rule.endAt(),
                    "invalid frozen counter rule");
        } catch (BizException | NullPointerException | ArithmeticException failure) {
            throw new IllegalArgumentException("invalid frozen counter seal", failure);
        }
    }

    private static void rawScope(String value, int max, boolean optional) {
        require(optional && value == null || value != null && value.length() <= max && (optional || !value.isBlank()),
                "invalid raw owner scope");
    }

    private static boolean validReference(String value) {
        return value != null && !value.isBlank() && value.length() <= 64 && value.equals(value.strip());
    }

    private static void reference(String value) {
        require(validReference(value), "invalid canonical counter reference");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
