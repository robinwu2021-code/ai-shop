package ai.neargo.shop.pay.storedvalue.settlement;

import ai.neargo.shop.spi.storedvalue.StoredValueSettlements.Adjustment;
import ai.neargo.shop.spi.storedvalue.StoredValueSettlements.Bill;
import ai.neargo.shop.spi.storedvalue.StoredValueSettlements.Decision;
import ai.neargo.shop.spi.storedvalue.StoredValueSettlements.Resolution;

/** Business contract; implementation preserves owner checks, transactions and immutable financial facts. */
public interface StoredValueSettlementDecisionService {
    Bill reconcile(Decision c);

    Bill dispute(Decision c);

    Bill resolve(Resolution c);

    Bill adjust(Adjustment c);
}
