package ai.neargo.shop.trade.mapper;

import ai.neargo.shop.trade.entity.OrdChannelAfterSale;
import ai.neargo.shop.trade.entity.OrdChannelAfterSaleLine;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

public final class ChannelAfterSaleMappers {
    private ChannelAfterSaleMappers() {}

    public interface CaseMapper extends BaseMapper<OrdChannelAfterSale> {}

    public interface LineMapper extends BaseMapper<OrdChannelAfterSaleLine> {}
}
