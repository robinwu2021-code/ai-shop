package ai.neargo.shop.pay.storedvalue.service;

import ai.neargo.shop.spi.storedvalue.StoredValueOrganizationImpactPort.Impact;



/** Stable business API; implementation keeps the original transaction and owner checks. */
public interface StoredValueOrganizationImpactService {
    Impact inspect(String tenant,String entity,String store);
}
