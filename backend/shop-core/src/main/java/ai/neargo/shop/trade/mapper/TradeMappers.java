package ai.neargo.shop.trade.mapper;

import ai.neargo.shop.trade.entity.OrdAfterSale;
import ai.neargo.shop.trade.entity.OrdInvoiceRequest;
import ai.neargo.shop.trade.entity.OrdItem;
import ai.neargo.shop.trade.entity.OrdOrder;
import ai.neargo.shop.trade.entity.OrdStatusLog;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.entity.TrdCartItem;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/** trade 域的 Mapper 集合。 */
public final class TradeMappers {

    private TradeMappers() {
    }

    public interface CartItemMapper extends BaseMapper<TrdCartItem> {
    }

    public interface OrderMapper extends BaseMapper<OrdOrder> {
    }

    public interface SubOrderMapper extends BaseMapper<OrdSubOrder> {

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
    }

    public interface OrderItemMapper extends BaseMapper<OrdItem> {
    }

    public interface StatusLogMapper extends BaseMapper<OrdStatusLog> {
    }

    public interface AfterSaleMapper extends BaseMapper<OrdAfterSale> {
    }

    public interface InvoiceRequestMapper extends BaseMapper<OrdInvoiceRequest> {
    }
}
