package ai.neargo.shop.promotion.policy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.promotion.dto.UnifiedActivityDefinition;
import ai.neargo.shop.spi.entitlement.ContractChecks;
import ai.neargo.shop.spi.entitlement.EntitlementDefinition.RedemptionScope;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Validates draft structure; reference ownership and live capabilities must additionally be checked by the owner. */
@Component
public class ActivityRuleValidator {
    public void validate(UnifiedActivityDefinition d) {
        try {
            ContractChecks.present(d);
            ContractChecks.require(d.name() != null && !d.name().isBlank() && d.name().length() <= 100);
            ContractChecks.require(Set.of("ORDER", "ENTITLEMENT_GRANT", "RECHARGE").contains(d.scene()));
            ContractChecks.id(d.templateCode());
            ContractChecks.require(d.templateCode().matches("AP[0-9]{3}") && d.templateVersion() > 0);
            ContractChecks.currency(d.currency());
            scope(d.storeParticipationScope());
            ContractChecks.require(Set.of("ALL", "STORES").contains(d.storeParticipationScope().mode()));
            ContractChecks.optionalId(d.audienceRef());
            ContractChecks.require(d.quotaLimit() == null || d.quotaLimit() > 0);
            ContractChecks.require(d.budgetLimitMinor() == null || d.budgetLimitMinor() >= 0);
            ContractChecks.require(d.perUserLimit() > 0);
            var s = ContractChecks.present(d.schedule());
            ContractChecks.require(Set.of("ONE_OFF", "ALWAYS_ON", "RECURRING").contains(s.type()));
            ContractChecks.present(s.startAt());
            ZoneId.of(s.zone());
            ContractChecks.require(s.endAt() == null || s.endAt().isAfter(s.startAt()));
            if ("ONE_OFF".equals(s.type())) ContractChecks.present(s.endAt());
            if ("ALWAYS_ON".equals(s.type())) {
                ContractChecks.require(d.quotaLimit() != null || d.budgetLimitMinor() != null);
            }
            if ("RECURRING".equals(s.type())) {
                ContractChecks.require(!s.weekdays().isEmpty() && s.weekdays().size() <= 7);
                ContractChecks.require(new HashSet<>(s.weekdays()).size() == s.weekdays().size());
                ContractChecks.require(s.weekdays().stream().allMatch(day -> day >= 1 && day <= 7));
                ContractChecks.require(LocalTime.parse(s.to()).isAfter(LocalTime.parse(s.from())));
            } else {
                ContractChecks.require(s.weekdays().isEmpty() && s.from() == null && s.to() == null);
            }
            var rule = ContractChecks.present(d.rule());
            ContractChecks.require(rule.schemaVersion() == 1);
            var trigger = ContractChecks.present(rule.trigger());
            ContractChecks.require(Set.of("NONE", "AMOUNT", "QTY", "GOODS").contains(trigger.type()));
            if ("AMOUNT".equals(trigger.type())) {
                ContractChecks.require(trigger.amountMinor() != null && trigger.amountMinor() > 0);
            } else ContractChecks.require(trigger.amountMinor() == null);
            if ("QTY".equals(trigger.type())) {
                ContractChecks.require(trigger.quantity() != null && trigger.quantity() > 0);
            } else ContractChecks.require(trigger.quantity() == null);
            if ("RECHARGE".equals(d.scene())) {
                ContractChecks.require("NONE".equals(trigger.type()) && rule.benefits().isEmpty());
                ContractChecks.require(!rule.tiers().isEmpty() && rule.tiers().size() <= 50);
                Set<String> tierNos = new HashSet<>();
                Set<Long> amounts = new HashSet<>();
                for (var tier : rule.tiers()) {
                    ContractChecks.id(tier.tierNo());
                    ContractChecks.require(tierNos.add(tier.tierNo()) && amounts.add(tier.payAmountMinor()));
                    ContractChecks.require(tier.payAmountMinor() > 0 && tier.bonusAmountMinor() >= 0);
                    Math.addExact(tier.payAmountMinor(), tier.bonusAmountMinor());
                    scope(tier.redemptionScope());
                }
            } else {
                ContractChecks.require(rule.tiers().isEmpty() && !rule.benefits().isEmpty());
                ContractChecks.require(rule.benefits().size() <= 10);
                for (var benefit : rule.benefits()) {
                    if ("ENTITLEMENT_GRANT".equals(d.scene())) {
                        ContractChecks.require("ENTITLEMENT".equals(benefit.kind()));
                        ContractChecks.id(benefit.templateVersionNo());
                        ContractChecks.require(benefit.amountMinor() == null && benefit.goodsNo() == null);
                        ContractChecks.require(benefit.quantity() != null && benefit.quantity() > 0);
                    } else {
                        ContractChecks.require(Set.of("CUT", "PRICE", "GIFT").contains(benefit.kind()));
                        ContractChecks.require(benefit.templateVersionNo() == null);
                        if ("GIFT".equals(benefit.kind())) {
                            ContractChecks.id(benefit.goodsNo());
                            ContractChecks.require(benefit.quantity() != null && benefit.quantity() > 0);
                            ContractChecks.require(benefit.amountMinor() == null);
                        } else {
                            ContractChecks.require(benefit.amountMinor() != null && benefit.amountMinor() > 0);
                            ContractChecks.require(benefit.goodsNo() == null && benefit.quantity() == null);
                        }
                        if (!"CUT".equals(benefit.kind())) ContractChecks.present(d.quotaLimit());
                    }
                }
            }
        } catch (RuntimeException invalid) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
    }

    public void validatePublication(UnifiedActivityDefinition d, Instant at) {
        validate(d);
        if (d.schedule().endAt() != null && !d.schedule().endAt().isAfter(at)) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
    }

    private static void scope(RedemptionScope scope) {
        ContractChecks.present(scope);
        ContractChecks.require(Set.of("ALL", "STORES", "GOODS", "CATEGORIES").contains(scope.mode()));
        ContractChecks.present(scope.refs());
        ContractChecks.require(scope.refs().size() <= 1000);
        scope.refs().forEach(ContractChecks::id);
        ContractChecks.require(new HashSet<>(scope.refs()).size() == scope.refs().size());
        ContractChecks.require("ALL".equals(scope.mode()) ? scope.refs().isEmpty() : !scope.refs().isEmpty());
    }
}
