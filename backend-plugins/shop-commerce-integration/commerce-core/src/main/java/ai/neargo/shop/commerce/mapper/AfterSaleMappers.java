package ai.neargo.shop.commerce.mapper;

import ai.neargo.shop.commerce.entity.CimAfterSaleLineLink;
import ai.neargo.shop.commerce.entity.CimAfterSaleLink;
import ai.neargo.shop.commerce.entity.CimAfterSaleRevision;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

public final class AfterSaleMappers {
    private AfterSaleMappers() {}

    public interface LinkMapper extends BaseMapper<CimAfterSaleLink> {}

    public interface RevisionMapper extends BaseMapper<CimAfterSaleRevision> {}

    public interface LineMapper extends BaseMapper<CimAfterSaleLineLink> {}
}
