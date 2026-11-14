package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.sql.DataSource;

/** Same genuine supplier/order/kitchen/custody workflow, with FOOD_LOCAL as the frozen authority. */
@TestPropertySource(properties="shop.food.local-stock.enabled=true")
class FoodChannelLocalStockNativeIT extends FoodChannelVerticalNativeIT {
    @Override @Test
    void actualTwoHandoversConserveOriginalQuantityAndReplayWithoutDuplicateSupplierEffects() throws Exception {
        var nativeInventory=new JdbcTemplate(context.getBean("invDataSource",DataSource.class));
        String balances="SELECT owner_id,location_id,item_id,on_hand,reserved,version FROM inv_stock_balance ORDER BY owner_id,location_id,item_id";
        var before=nativeInventory.queryForList(balances);
        int original=sql.queryForObject("SELECT COUNT(*) FROM ord_channel_preparation WHERE inventory_owner='FOOD_LOCAL'",Integer.class);
        super.actualTwoHandoversConserveOriginalQuantityAndReplayWithoutDuplicateSupplierEffects();
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM ord_channel_preparation WHERE inventory_owner='FOOD_LOCAL'",Integer.class))
                .isGreaterThan(original);
        assertThat(nativeInventory.queryForList(balances)).isEqualTo(before);
    }
}
