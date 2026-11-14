package ai.neargo.shop.trade.order.mapper;

import ai.neargo.shop.trade.entity.OrdSubOrder;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Select;

/** 订单域持久化：沿用原表、平台工厂及SQL语义。 */
public interface StoreOrderMapper extends BaseMapper<OrdSubOrder> {

    /**
     * 给预约名额的释放<b>打幂等标记</b>：只有影响到 1 行的那次才有资格去减 booked。
     *
     * <p>取消会被重放 —— 超时关闭与用户手动取消可能同时到达，而两条路
     * 都会走释放。少了这道闸，名额被还两次，booked 减成负数，
     * 此后这个时段能卖出比 capacity 更多的单，<b>而且不会有任何报错</b>。
     *
     * <p><b>顺序不能反</b>：先打标记再减。反过来的话，两个并发线程可能
     * 都先减成功、再各自去打标记，标记那一步的互斥就白做了。
     *
     * @return 1 = 这次由你来还；0 = 别人已经还过了，什么都别做
     */
    @Update("""
            UPDATE ord_sub_order SET appointment_released_at = #{at}, version = version + 1
            WHERE sub_order_no = #{subOrderNo} AND deleted = 0
              AND appointment_slot_no IS NOT NULL AND appointment_released_at IS NULL
            """)
    int markAppointmentReleased(@Param("subOrderNo") String subOrderNo, @Param("at") long at);

    /** Source ownership follows persisted original references, never client labels or lifecycle guesses. */
    @Select("""
            SELECT COUNT(*) FROM ord_industry_order_source
            WHERE tenant_no = #{tenantNo} AND entity_no = #{entityNo} AND store_no = #{storeNo}
              AND order_no = #{orderNo} AND sub_order_no = #{subOrderNo} AND deleted = 0
            """)
    long countIndustrySources(@Param("tenantNo") String tenantNo, @Param("entityNo") String entityNo,
                              @Param("storeNo") String storeNo, @Param("orderNo") String orderNo,
                              @Param("subOrderNo") String subOrderNo);

    /** A buyer may confirm only after the source-aware owner persisted the original physical receipt. */
    @Select("""
            SELECT COUNT(*) FROM ord_industry_order_source s
            JOIN ord_industry_fulfillment f ON f.tenant_no = s.tenant_no AND f.entity_no = s.entity_no
              AND f.store_no = s.store_no AND f.order_no = s.order_no AND f.sub_order_no = s.sub_order_no
              AND f.source_system = s.source_system AND f.source_type = s.source_type
              AND f.source_no = s.source_no AND f.source_version = s.source_version AND f.source_hash = s.source_hash
            WHERE s.tenant_no = #{tenantNo} AND s.entity_no = #{entityNo} AND s.store_no = #{storeNo}
              AND s.order_no = #{orderNo} AND s.sub_order_no = #{subOrderNo}
              AND s.deleted = 0 AND f.deleted = 0 AND f.status IN ('BUYER_CONFIRM_PENDING', 'VERIFIED')
              AND f.inventory_receipt_json IS NOT NULL
            """)
    long countIndustryFulfillmentReceipts(@Param("tenantNo") String tenantNo, @Param("entityNo") String entityNo,
                                         @Param("storeNo") String storeNo, @Param("orderNo") String orderNo,
                                         @Param("subOrderNo") String subOrderNo);

}
