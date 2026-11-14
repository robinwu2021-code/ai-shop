package ai.neargo.shop.pay.storedvalue.issuer;

import ai.neargo.shop.pay.storedvalue.entity.SvLot;
import ai.neargo.shop.pay.storedvalue.policy.StoredValueAllocationPolicy.LotBalance;
import java.util.List;

/** Stable PAY business contract; original owner checks and transaction boundaries remain in its implementation. */
public interface StoredValueLotBasisService {
    List<LotBalance> available(String tenant,List<SvLot> lots);
}
