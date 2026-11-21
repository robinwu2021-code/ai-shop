package ai.neargo.shop.payclient;

import ai.neargo.shop.pay.storedvalue.service.StoredValueConsumerQueryService;

/** One consumer query contract keeps embedded and standalone Pay permission semantics identical. */
public interface StoredValueConsumerQueryAppService extends StoredValueConsumerQueryService { }
