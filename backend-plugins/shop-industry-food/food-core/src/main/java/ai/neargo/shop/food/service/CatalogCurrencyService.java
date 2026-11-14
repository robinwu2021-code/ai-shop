package ai.neargo.shop.food.service;

import ai.neargo.shop.food.policy.CatalogCurrencySession;

/** Original market facts are captured outside local transactions, independently of any existing menu. */
public interface CatalogCurrencyService {
    /** Each command receives its own evidence window so concurrent requests cannot share mutable context. */
    CatalogCurrencySession open(String entityNo, String storeNo);
}
