package ai.neargo.shop.pay.storedvalue.issuer;

import ai.neargo.shop.spi.storedvalue.StoredValueBrandActivation.Ack;
import ai.neargo.shop.spi.storedvalue.StoredValueBrandActivation.Prepare;

/** Stable PAY business contract; original owner checks and transaction boundaries remain in its implementation. */
public interface StoredValueBrandActivationService {
    Ack activate(Prepare command);
}
