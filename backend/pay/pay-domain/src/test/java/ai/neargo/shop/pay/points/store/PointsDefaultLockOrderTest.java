package ai.neargo.shop.pay.points.store;

import ai.neargo.shop.pay.mapper.points.PointsConfigurationMapper;
import ai.neargo.shop.pay.points.store.impl.AcceptanceAvailabilityProjection;
import ai.neargo.shop.pay.points.store.impl.MybatisPointsConfigurationStore;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;

class PointsDefaultLockOrderTest {
    @Test void market_default_takes_individual_type_locks_in_global_switch_order() {
        var mapper = mock(PointsConfigurationMapper.class);
        when(mapper.marketTypes("CN")).thenReturn(List.of("B", "A"));
        var store = new MybatisPointsConfigurationStore(mapper, mock(AcceptanceAvailabilityProjection.class));
        store.lockMarketTypes("CN");
        var order = inOrder(mapper);
        order.verify(mapper).marketTypes("CN");
        order.verify(mapper).lockType("A");
        order.verify(mapper).lockType("B");
        order.verifyNoMoreInteractions();
    }
}
