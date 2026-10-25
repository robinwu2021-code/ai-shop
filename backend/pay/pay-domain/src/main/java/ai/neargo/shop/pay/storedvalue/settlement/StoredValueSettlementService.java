package ai.neargo.shop.pay.storedvalue.settlement;

import ai.neargo.shop.spi.storedvalue.StoredValueSettlements.Generated;
import ai.neargo.shop.spi.storedvalue.StoredValueSettlements.Generation;

/** Business contract; implementation preserves owner checks, transactions and immutable financial facts. */
public interface StoredValueSettlementService {
    Generated generate(Generation c);
}
