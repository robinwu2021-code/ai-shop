package ai.neargo.shop.pay.storedvalue.policy;

import ai.neargo.shop.pay.storedvalue.entity.SvAccount;
import ai.neargo.shop.pay.storedvalue.entity.SvLot;
import ai.neargo.shop.pay.storedvalue.policy.StoredValueAllocationPolicy.LotBalance;
import ai.neargo.shop.spi.entitlement.EntitlementDefinition.RedemptionScope;
import ai.neargo.shop.spi.entitlement.EntitlementUseSourcePort.UseSourceFact;
import java.util.List;
import tools.jackson.databind.ObjectMapper;

/** Money conservation is checked against the actual account and its remaining, including fully-held, lots. */
public final class StoredValueUseFacts {
    private StoredValueUseFacts() { }
    public static List<LotBalance> available(List<SvLot> lots, ObjectMapper json) {
        return lots.stream().map(l -> new LotBalance(l.getLotNo(), l.getPrincipalMinor() - l.getHeldPrincipalMinor(),
                l.getBonusMinor() - l.getHeldBonusMinor(), json.readValue(l.getScopeSnapshot(), RedemptionScope.class),
                StoredValueFacts.instant(l.getCreditedAt()))).toList();
    }
    public static void conserve(SvAccount account, List<SvLot> lots) {
        long principal = 0, bonus = 0, heldPrincipal = 0, heldBonus = 0;
        for (var lot : lots) {
            if (!account.getAccountNo().equals(lot.getAccountNo())) StoredValueFacts.conflict();
            principal = Math.addExact(principal, lot.getPrincipalMinor());
            bonus = Math.addExact(bonus, lot.getBonusMinor());
            heldPrincipal = Math.addExact(heldPrincipal, lot.getHeldPrincipalMinor());
            heldBonus = Math.addExact(heldBonus, lot.getHeldBonusMinor());
        }
        if (principal != account.getPrincipalMinor() || bonus != account.getBonusMinor()
                || heldPrincipal != account.getHeldPrincipalMinor() || heldBonus != account.getHeldBonusMinor()) {
            StoredValueFacts.conflict();
        }
    }
    public static long amount(UseSourceFact source) {
        long amount = 0;
        for (var item : source.items()) {
            if (item.amountMinor() <= 0) StoredValueFacts.conflict();
            amount = Math.addExact(amount, item.amountMinor());
        }
        return amount;
    }
}
