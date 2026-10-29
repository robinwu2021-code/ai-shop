package ai.neargo.shop.trade.stock;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;

/** Pure policy; the caller must establish the source through the original industry owner first. */
public final class IndustryStockOwnerPolicy {
    public static final String FOOD_LOCAL="FOOD_LOCAL";
    public static final String LEGACY="LEGACY_INVENTORY";
    private IndustryStockOwnerPolicy(){}
    public static String newOwner(String verifiedSourceSystem,boolean foodLocalEnabled) {
        if(verifiedSourceSystem==null||verifiedSourceSystem.isBlank())throw conflict();
        return "FOOD".equals(verifiedSourceSystem)&&foodLocalEnabled?FOOD_LOCAL:LEGACY;
    }
    public static String frozenOwner(String owner,String originalAuthority) {
        if(FOOD_LOCAL.equals(owner)||LEGACY.equals(owner))return owner;
        if(owner==null&&"INDUSTRY_INVENTORY_V2".equals(originalAuthority))return LEGACY;
        throw conflict();
    }
    public static void same(String intentOwner,String sourceOwner,String originalAuthority) {
        if(!frozenOwner(intentOwner,originalAuthority).equals(frozenOwner(sourceOwner,originalAuthority)))throw conflict();
    }
    private static BizException conflict(){return BizException.of(ErrorCode.CONFLICT);}
}
