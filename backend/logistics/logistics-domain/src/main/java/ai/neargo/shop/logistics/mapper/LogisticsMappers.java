package ai.neargo.shop.logistics.mapper;

import ai.neargo.shop.logistics.entity.LgsCarrierCode;
import ai.neargo.shop.logistics.entity.LgsWaybill;
import ai.neargo.shop.logistics.entity.LgsWaybillNode;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/** logistics 域的 Mapper 集合。 */
public final class LogisticsMappers {

    private LogisticsMappers() {
    }

    public interface CarrierCodeMapper extends BaseMapper<LgsCarrierCode> {
    }

    public interface WaybillMapper extends BaseMapper<LgsWaybill> {
    }

    public interface WaybillNodeMapper extends BaseMapper<LgsWaybillNode> {
    }
}
