package ai.neargo.shop.trade.stock;
import ai.neargo.shop.common.BizException;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class IndustryStockOwnerPolicyTest {
    @Test void newRestaurantSelectionDoesNotSwitchHistoricalOrOtherIndustryOwners(){
        assertThat(IndustryStockOwnerPolicy.newOwner("FOOD",true)).isEqualTo("FOOD_LOCAL");
        assertThat(IndustryStockOwnerPolicy.newOwner("RETAIL",true)).isEqualTo("LEGACY_INVENTORY");
        assertThat(IndustryStockOwnerPolicy.newOwner("BEAUTY",true)).isEqualTo("LEGACY_INVENTORY");
        assertThat(IndustryStockOwnerPolicy.frozenOwner(null,"INDUSTRY_INVENTORY_V2")).isEqualTo("LEGACY_INVENTORY");
        assertThat(IndustryStockOwnerPolicy.frozenOwner("FOOD_LOCAL","INDUSTRY_INVENTORY_V2")).isEqualTo("FOOD_LOCAL");}
    @Test void unknownOrInconsistentOwnersFailClosed(){
        assertThatThrownBy(()->IndustryStockOwnerPolicy.frozenOwner(null,"UNKNOWN")).isInstanceOf(BizException.class);
        assertThatThrownBy(()->IndustryStockOwnerPolicy.frozenOwner("ARBITRARY","INDUSTRY_INVENTORY_V2")).isInstanceOf(BizException.class);
        assertThatThrownBy(()->IndustryStockOwnerPolicy.same("FOOD_LOCAL","LEGACY_INVENTORY","INDUSTRY_INVENTORY_V2")).isInstanceOf(BizException.class);}
}
