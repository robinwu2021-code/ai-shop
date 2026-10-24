package ai.neargo.shop.food.mapper;

import ai.neargo.shop.food.entity.FnbReservationTable;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Select;

/** Queries must run after the existing area/table lock, shared by every occupancy writer. */
public interface ReservationTableMapper extends BaseMapper<FnbReservationTable> {
    @Delete("DELETE FROM fnb_reservation_table WHERE tenant_no='MAIN' AND entity_no=#{entity} "
            + "AND store_no=#{store} AND reservation_no=#{reservation}")
    int clear(@Param("entity") String entity,@Param("store") String store,@Param("reservation") String reservation);

    @Select("SELECT COUNT(*) FROM fnb_reservation_table t JOIN fnb_reservation r "
            + "ON r.tenant_no=t.tenant_no AND r.entity_no=t.entity_no AND r.store_no=t.store_no "
            + "AND r.reservation_no=t.reservation_no WHERE t.tenant_no='MAIN' AND t.entity_no=#{entity} "
            + "AND t.store_no=#{store} AND t.table_no=#{table} AND t.deleted=0 AND r.deleted=0 "
            + "AND r.status IN ('HELD','CONFIRMED') AND r.start_at < #{end} AND r.end_at > #{start} "
            + "AND (#{except} IS NULL OR r.reservation_no <> #{except})")
    long overlaps(@Param("entity") String entity,@Param("store") String store,@Param("table") String table,
            @Param("start") LocalDateTime start,@Param("end") LocalDateTime end,@Param("except") String except);

    @Select("SELECT COUNT(*) FROM fnb_reservation_table t JOIN fnb_reservation r "
            + "ON r.tenant_no=t.tenant_no AND r.entity_no=t.entity_no AND r.store_no=t.store_no "
            + "AND r.reservation_no=t.reservation_no WHERE t.tenant_no='MAIN' AND t.entity_no=#{entity} "
            + "AND t.store_no=#{store} AND t.table_no=#{table} AND t.deleted=0 AND r.deleted=0 "
            + "AND r.status IN ('HELD','CONFIRMED') AND r.end_at > #{now}")
    long pending(@Param("entity") String entity,@Param("store") String store,@Param("table") String table,
            @Param("now") LocalDateTime now);
}
