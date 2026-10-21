package ai.neargo.shop.food.mapper;

import ai.neargo.shop.food.entity.FnbActionLog;
import ai.neargo.shop.food.entity.FnbDish;
import ai.neargo.shop.food.entity.FnbCheck;
import ai.neargo.shop.food.entity.FnbGuestSession;
import ai.neargo.shop.food.entity.FnbReservation;
import ai.neargo.shop.food.entity.FnbSeat;
import ai.neargo.shop.food.entity.FnbServiceCalendar;
import ai.neargo.shop.food.entity.FnbStorePolicy;
import ai.neargo.shop.food.entity.FnbTable;
import ai.neargo.shop.food.entity.FnbTableArea;
import ai.neargo.shop.food.entity.FnbTableOccupancy;
import ai.neargo.shop.food.entity.FnbTableOperation;
import ai.neargo.shop.food.entity.FnbTableToken;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 按实际落地的持久化资源增加嵌套 Mapper，不预生成全域空壳。 */
public final class FoodMappers {
    private FoodMappers() {
    }

    public interface DishMapper extends BaseMapper<FnbDish> { }

    public interface TableTokenMapper extends BaseMapper<FnbTableToken> { }

    public interface GuestSessionMapper extends BaseMapper<FnbGuestSession> { }

    public interface CheckMapper extends BaseMapper<FnbCheck> {
        // Presence is intentionally fail-closed, including historical or anomalously deleted facts.
        @Select("SELECT (SELECT COUNT(*) FROM fnb_order_batch WHERE entity_no=#{entity} AND check_no=#{check})"
                +"+(SELECT COUNT(*) FROM fnb_check_batch_membership WHERE entity_no=#{entity} AND check_no=#{check})"
                +"+(SELECT COUNT(*) FROM fnb_settlement_item WHERE entity_no=#{entity} AND check_no=#{check})"
                +"+(SELECT COUNT(*) FROM fnb_return WHERE entity_no=#{entity} AND check_no=#{check})"
                +"+(SELECT COUNT(*) FROM fnb_dining_entitlement WHERE entity_no=#{entity} AND check_no=#{check})"
                +"+(SELECT COUNT(*) FROM fnb_check_cart WHERE entity_no=#{entity} AND check_no=#{check} AND (status IS NULL OR status<>'CLOSED'))")
        long tradeFacts(@Param("entity") String entity,@Param("check") String check);
    }
    public interface TableOccupancyMapper extends BaseMapper<FnbTableOccupancy> {
        @Select("SELECT * FROM fnb_table_occupancy WHERE entity_no=#{entity} AND table_no=#{table} AND ended_at IS NULL ORDER BY occupancy_no")
        List<FnbTableOccupancy> activeForTable(@Param("entity") String entity,@Param("table") String table);
        @Select("SELECT * FROM fnb_table_occupancy WHERE entity_no=#{entity} AND check_no=#{check} AND ended_at IS NULL ORDER BY occupancy_no")
        List<FnbTableOccupancy> activeForCheck(@Param("entity") String entity,@Param("check") String check);
    }
    public interface SeatMapper extends BaseMapper<FnbSeat> {
        @Select("SELECT * FROM fnb_seat WHERE entity_no=#{entity} AND check_no=#{check} ORDER BY seat_no")
        List<FnbSeat> history(@Param("entity") String entity,@Param("check") String check);
        @Update("UPDATE fnb_seat SET label=#{label},guest_ref=NULL,deleted=#{deleted},version=version+1,"
                +"updated_by=#{actor},updated_at=#{at} WHERE entity_no=#{entity} AND check_no=#{check} "
                +"AND seat_no=#{seat} AND version=#{version}")
        int save(@Param("entity") String entity,@Param("check") String check,@Param("seat") String seat,
                @Param("version") long version,@Param("label") String label,@Param("deleted") int deleted,
                @Param("actor") String actor,@Param("at") LocalDateTime at);
    }
    public interface TableOperationMapper extends BaseMapper<FnbTableOperation> {
        // Intent facts cannot be ignored just because a projection was logically archived.
        @Select("SELECT COUNT(*) FROM fnb_table_operation WHERE entity_no=#{entity} AND (check_no=#{check} OR target_check_no=#{check}) "
                +"AND (resource_operation_ref IS NOT NULL OR status IS NULL OR status NOT IN ('APPLIED','FAILED'))")
        long externalOrPendingForCheck(@Param("entity") String entity,@Param("check") String check);
    }

    public interface ReservationMapper extends BaseMapper<FnbReservation> { }

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
