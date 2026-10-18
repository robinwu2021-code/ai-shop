package ai.neargo.shop.spi.product;

import java.util.Set;

/** Product owner checks business IDs directly; a display DTO or SKU lookup cannot prove scope ownership. */
public interface ProductScopeFactsPort {
    /** Includes draft/off-sale goods; new-sale eligibility is checked again at participation/use time. */
    boolean goodsOwned(String tenantNo, String entityNo, Set<String> goodsNos);

    /** Platform categories have no merchant owner; require active category facts within the tenant. */
    boolean categoriesActive(String tenantNo, Set<String> categoryNos);
}
