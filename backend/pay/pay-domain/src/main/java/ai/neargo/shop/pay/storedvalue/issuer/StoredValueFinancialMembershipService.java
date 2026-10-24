package ai.neargo.shop.pay.storedvalue.issuer;

import ai.neargo.shop.spi.storedvalue.StoredValueMembership.Ack;
import ai.neargo.shop.spi.storedvalue.StoredValueMembership.Commit;
import ai.neargo.shop.spi.storedvalue.StoredValueMembership.Describe;
import ai.neargo.shop.spi.storedvalue.StoredValueMembership.Prepare;
import ai.neargo.shop.spi.storedvalue.StoredValueMembership.Target;
import java.util.List;

/** Business contract; implementation preserves owner checks, transactions and immutable financial facts. */
public interface StoredValueFinancialMembershipService {
    List<Target> describe(Describe c);

    Ack prepare(Prepare c);

    Ack commit(Commit c);
}
