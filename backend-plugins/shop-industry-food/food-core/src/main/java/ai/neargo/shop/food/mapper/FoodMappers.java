package ai.neargo.shop.food.mapper;

import ai.neargo.shop.food.entity.FnbActionLog;
import ai.neargo.shop.food.entity.FnbServiceCalendar;
import ai.neargo.shop.food.entity.FnbStorePolicy;
import ai.neargo.shop.food.entity.FnbTable;
import ai.neargo.shop.food.entity.FnbTableArea;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 按实际落地的持久化资源增加嵌套 Mapper，不预生成全域空壳。 */
public final class FoodMappers {
    private FoodMappers() {
    }

    public interface ActionLogMapper extends BaseMapper<FnbActionLog> {
    }

    public interface ServiceCalendarMapper extends BaseMapper<FnbServiceCalendar> {
    }

    public interface StorePolicyMapper extends BaseMapper<FnbStorePolicy> {
    }

    public interface TableMapper extends BaseMapper<FnbTable> {
        @Select("SELECT COUNT(*) FROM fnb_table_occupancy WHERE entity_no=#{entity} "
                + "AND table_no=#{table} AND ended_at IS NULL")
        long liveOccupancies(@Param("entity") String entity, @Param("table") String table);

        @Update("UPDATE fnb_table SET deleted=1,version=version+1,updated_at=CURRENT_TIMESTAMP(3),updated_by=#{actor} "
                + "WHERE entity_no=#{entity} AND store_no=#{store} AND table_no=#{table} "
                + "AND version=#{version} AND deleted=0")
        int archive(@Param("entity") String entity, @Param("store") String store, @Param("table") String table,
                @Param("version") long version, @Param("actor") String actor);
    }

    public interface TableAreaMapper extends BaseMapper<FnbTableArea> {
        @Select("SELECT COUNT(*) FROM fnb_table t WHERE t.entity_no=#{entity} AND t.store_no=#{store} "
                + "AND t.area_no=#{area} AND t.deleted=0 AND t.status='ACTIVE'")
        long activeTables(@Param("entity") String entity, @Param("store") String store, @Param("area") String area);

        // An anomalous occupancy must still block even if its table was disabled or logically deleted.
        @Select("SELECT COUNT(*) FROM fnb_table t JOIN fnb_table_occupancy o ON o.table_no=t.table_no "
                + "AND o.entity_no=t.entity_no WHERE t.entity_no=#{entity} AND t.store_no=#{store} "
                + "AND t.area_no=#{area} AND o.ended_at IS NULL")
        long liveOccupancies(@Param("entity") String entity, @Param("store") String store, @Param("area") String area);
    }
}
