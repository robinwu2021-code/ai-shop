package ai.neargo.shop.trade.order.mapper;

import ai.neargo.shop.trade.order.entity.OrdShipment;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** 发货单 mapper（订单服务 2.0）。本阶段空置待用——接线阶段纳入 MapperScan。 */
@Mapper
public interface ShipmentMapper extends BaseMapper<OrdShipment> {
}
