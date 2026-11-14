package ai.neargo.shop.pay.storedvalue.settlement;

import ai.neargo.shop.spi.storedvalue.StoredValueSettlements.Authority;
import ai.neargo.shop.spi.storedvalue.StoredValueSettlements.Payment;
import ai.neargo.shop.spi.storedvalue.StoredValueSettlements.Receipt;
import ai.neargo.shop.spi.storedvalue.StoredValueSettlements.Registration;
import ai.neargo.shop.spi.storedvalue.StoredValueSettlements.VoidPayment;

/** Business contract; implementation preserves owner checks, transactions and immutable financial facts. */
public interface StoredValueSettlementPaymentService {
    Payment register(Registration c);

    Payment confirm(Receipt c);

    Payment voidRegistration(VoidPayment c);

    Payment resolve(Authority a,String billNo,String key);
}
