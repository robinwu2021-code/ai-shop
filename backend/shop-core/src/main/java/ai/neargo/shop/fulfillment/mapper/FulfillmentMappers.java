package ai.neargo.shop.fulfillment.mapper;

import ai.neargo.shop.fulfillment.entity.FulBatch;
import ai.neargo.shop.fulfillment.entity.FulCarrier;
import ai.neargo.shop.fulfillment.entity.FulFreightTemplate;
import ai.neargo.shop.fulfillment.entity.FulGroupPickup;
import ai.neargo.shop.fulfillment.entity.FulShipment;
import ai.neargo.shop.fulfillment.entity.FulShipmentTrace;
import ai.neargo.shop.fulfillment.entity.FulShortageReport;
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

    /** 到货批次（P-5.1.1）。V1 建表、V130 补调度列，此前**没有任何读写方**。 */
    public interface BatchMapper extends BaseMapper<FulBatch> {
    }

    /** 自提点缺件上报（P-5.1.2）。 */
    public interface ShortageReportMapper extends BaseMapper<FulShortageReport> {
    }

    public interface ShipmentMapper extends BaseMapper<FulShipment> {
    }

    public interface ShipmentTraceMapper extends BaseMapper<FulShipmentTrace> {
    }

    public interface FreightTemplateMapper extends BaseMapper<FulFreightTemplate> {
    }

    public interface CarrierMapper extends BaseMapper<FulCarrier> {
    }
}
