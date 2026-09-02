package ai.neargo.shop.marketing.visit.mapper;

import ai.neargo.shop.marketing.visit.entity.MktStoreVisit;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/** 访问埋点域的 Mapper 集合。 */
public final class VisitMappers {

    private VisitMappers() {
    }

    public interface StoreVisitMapper extends BaseMapper<MktStoreVisit> {
    }
}
