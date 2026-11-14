package ai.neargo.shop.spi.marketing;

import java.util.List;

/** Authoritative organization facts for ordinary promotion, not a grant of user permissions. */
public interface BrandPromotionScopePort {
    record Store(String storeNo,String entityNo,long storeRevision,String bindingNo,long bindingRevision,String market) {}
    record Brand(String managementNo,String brandNo,String legalEntityNo,long revision,long authorityRevision,List<Store> stores) {
        public Brand {stores=List.copyOf(stores);}
    }
    /** Current active members. Publication additionally checks the caller's resource permissions. */
    Brand find(String tenantNo,String managementNo,String brandNo,String legalEntityNo);
    /** Actual current consumer ownership; historical coupon scopes retain their published stores. */
    boolean storeBelongsTo(String tenantNo,String storeNo,String entityNo);
    /** Organization membership only; Core still checks its immutable published participation scope. */
    List<String> activeBrands(String tenantNo,String storeNo,String entityNo);
}
