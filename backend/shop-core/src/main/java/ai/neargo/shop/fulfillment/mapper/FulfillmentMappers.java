package ai.neargo.shop.fulfillment.mapper;

import ai.neargo.shop.fulfillment.entity.FulGroupPickup;
import ai.neargo.shop.fulfillment.entity.FulVerifyLog;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/** fulfillment 域的 Mapper 集合。 */
public final class FulfillmentMappers {

    private FulfillmentMappers() {
    }

    public interface VerifyLogMapper extends BaseMapper<FulVerifyLog> {
    }

    public interface GroupPickupMapper extends BaseMapper<FulGroupPickup> {
    }
}
