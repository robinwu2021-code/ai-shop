package ai.neargo.shop.food.mapper;

import ai.neargo.shop.food.entity.FnbActionLog;
import ai.neargo.shop.food.entity.FnbStorePolicy;
import ai.neargo.shop.food.entity.FnbTableArea;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/** 按实际落地的持久化资源增加嵌套 Mapper，不预生成全域空壳。 */
public final class FoodMappers {
    private FoodMappers() {
    }

    public interface ActionLogMapper extends BaseMapper<FnbActionLog> {
    }

    public interface StorePolicyMapper extends BaseMapper<FnbStorePolicy> {
    }

    public interface TableAreaMapper extends BaseMapper<FnbTableArea> {
    }
}
