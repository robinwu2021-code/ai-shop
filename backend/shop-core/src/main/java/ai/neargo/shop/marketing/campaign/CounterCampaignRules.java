package ai.neargo.shop.marketing.campaign;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.marketing.campaign.entity.MktCampaign;
import ai.neargo.shop.spi.marketing.CampaignPort.AppliedActivity;
import ai.neargo.shop.spi.marketing.CampaignPort.CounterActivitySeal;
import ai.neargo.shop.spi.marketing.CampaignPort.CounterRule;
import ai.neargo.shop.spi.marketing.CampaignPort.MerchantAmount;
import ai.neargo.shop.spi.marketing.CounterCampaignContract;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort.CustomerParty;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/** Owner-local objective legacy facts; database scoping and fences remain the actual owner's responsibility. */
public final class CounterCampaignRules {
    private CounterCampaignRules() {}

    /** Malformed goods JSON must never broaden a scoped owner rule into a whole-group discount. */
    public static List<String> goodsNos(ObjectMapper json, String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            var reader = json.reader().with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
            var node = reader.readTree(raw);
            if (node.isString()) {
                node = reader.readTree(node.asString());
            }
            if (!node.isArray()) {
                throw new IllegalArgumentException("counter goods scope must be an array");
            }
            var goods = new TreeSet<String>();
            for (var value : node) {
                if (!value.isString() || !reference(value.asString())) {
                    throw new IllegalArgumentException("invalid counter goods reference");
                }
                goods.add(value.asString());
            }
            return List.copyOf(goods);
        } catch (RuntimeException failure) {
            throw unavailable();
        }
    }

    /** Inclusive legacy scheduling uses the caller's single fenced owner time, never an ambient clock. */
    public static boolean active(MktCampaign row, long ownerNowMillis) {
        if (row == null || !Integer.valueOf(0).equals(row.getDeleted()) || !"RUNNING".equals(row.getStatus())) {
            return false;
        }
        if (row.getStartAt() == null || row.getEndAt() == null || row.getStartAt() > row.getEndAt()) {
            throw unavailable();
        }
        return row.getStartAt() <= ownerNowMillis && ownerNowMillis <= row.getEndAt();
    }

    /** Whole-group FULL_CUT is the only supported discount; threshold null retains legacy zero semantics. */
    public static boolean supportedEligible(MktCampaign row, List<String> goodsNos, MerchantAmount group,
            long ownerNowMillis) {
        canonical(group);
        requireGoods(goodsNos);
        boolean eligible = row != null && "FULL_CUT".equals(row.getType()) && goodsNos.isEmpty()
                && active(row, ownerNowMillis) && positiveCut(row)
                && group.goodsAmount() >= (row.getThresholdMinor() == null ? 0 : row.getThresholdMinor());
        if (eligible && (row.getVersion() == null || row.getVersion() < 0)) {
            throw unavailable();
        }
        return eligible;
    }

    /** Scoped discounts conservatively use whole-group threshold after actual purchased-goods intersection. */
    public static boolean scopedFullCutEffect(MktCampaign row, List<String> goodsNos, MerchantAmount group,
            long ownerNowMillis) {
        canonical(group);
        requireGoods(goodsNos);
        return row != null && "FULL_CUT".equals(row.getType()) && !goodsNos.isEmpty()
                && intersects(goodsNos, group) && active(row, ownerNowMillis) && positiveCut(row)
                && group.goodsAmount() >= (row.getThresholdMinor() == null ? 0 : row.getThresholdMinor());
    }

    /** Mandatory purchased-goods price/gift effects cannot be opted out through NONE or an explicit discount. */
    public static boolean mandatoryPriceOrGiftEffect(MktCampaign row, List<String> goodsNos, MerchantAmount group,
            long ownerNowMillis) {
        canonical(group);
        requireGoods(goodsNos);
        if (row == null || !("FLASH".equals(row.getType()) || "BUY_GIFT".equals(row.getType()))
                || !intersects(goodsNos, group) || !active(row, ownerNowMillis)) {
            return false;
        }
        if ("FLASH".equals(row.getType())) {
            if (row.getFlashPriceMinor() == null || row.getFlashPriceMinor() <= 0) {
                throw unavailable();
            }
        } else if (row.getBuyN() == null || row.getBuyN() <= 0 || row.getGiftM() == null || row.getGiftM() <= 0) {
            throw unavailable();
        }
        return true;
    }

    /** Preserve raw scoped owner facts while the selected amount is calculated from the canonical group. */
    public static CounterRule selectedRule(MktCampaign row, List<String> goodsNos, MerchantAmount group,
            long ownerNowMillis) {
        if (!supportedEligible(row, goodsNos, group, ownerNowMillis)) {
            throw unavailable();
        }
        return new CounterRule(row.getTenantNo(), row.getCampaignNo(), row.getEntityNo(), row.getStoreNo(),
                row.getVersion(), row.getType(), row.getStatus(), row.getDeleted(), row.getThresholdMinor(),
                row.getDiscountMinor(), row.getStartAt(), row.getEndAt(), goodsNos,
                Math.min(row.getDiscountMinor(), group.goodsAmount()), "MERCHANT", 0);
    }

    /** Candidate coordinates must match the canonical order group even when raw SQL scope aliases differ. */
    public static AppliedActivity candidate(MktCampaign row, List<String> goodsNos, MerchantAmount group,
            long ownerNowMillis) {
        CounterRule rule = selectedRule(row, goodsNos, group, ownerNowMillis);
        return new AppliedActivity(rule.activityNo(), group.merchantNo(), rule.appliedMinor(), 1, 0, null,
                row.getName(), group.storeNo());
    }

    /** Translate owner construction failures at its boundary while delegating the only protocol algorithm to SPI. */
    public static CounterActivitySeal seal(ObjectMapper json, CustomerParty party, List<MerchantAmount> groups,
            Map<String, String> choices, List<CounterRule> selectedRules) {
        try {
            return CounterCampaignContract.seal(json, party, groups, choices, selectedRules);
        } catch (RuntimeException failure) {
            throw unavailable();
        }
    }

    private static void canonical(MerchantAmount group) {
        try {
            CounterCampaignContract.canonicalGroups(List.of(group));
        } catch (RuntimeException failure) {
            throw unavailable();
        }
    }

    private static void requireGoods(List<String> goodsNos) {
        if (goodsNos == null || goodsNos.stream().anyMatch(g -> !reference(g))) {
            throw unavailable();
        }
    }

    private static boolean positiveCut(MktCampaign row) {
        return row.getDiscountMinor() != null && row.getDiscountMinor() > 0;
    }

    private static boolean intersects(List<String> goodsNos, MerchantAmount group) {
        return group.lines().stream().anyMatch(line -> goodsNos.contains(line.goodsNo()));
    }

    private static boolean reference(String value) {
        return value != null && !value.isBlank() && value.length() <= 64 && value.equals(value.strip());
    }

    private static BizException unavailable() {
        return BizException.of(ErrorCode.CHECKOUT_DEPENDENCY_UNAVAILABLE);
    }
}
