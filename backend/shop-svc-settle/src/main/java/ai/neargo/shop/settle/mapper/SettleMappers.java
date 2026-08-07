package ai.neargo.shop.settle.mapper;

import ai.neargo.shop.settle.entity.StlBill;
import ai.neargo.shop.settle.entity.StlSplitLog;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/** settle 域的 Mapper 集合。 */
public final class SettleMappers {

    private SettleMappers() {
    }

    public interface BillMapper extends BaseMapper<StlBill> {
    }

    public interface SplitLogMapper extends BaseMapper<StlSplitLog> {
    }
}
