package ai.neargo.shop.inventory.mapper;

import ai.neargo.shop.inventory.entity.InvInboundLine;
import ai.neargo.shop.inventory.entity.InvInboundOrder;
import ai.neargo.shop.inventory.entity.InvItem;
import ai.neargo.shop.inventory.entity.InvItemRef;
import ai.neargo.shop.inventory.entity.InvLedger;
import ai.neargo.shop.inventory.entity.InvLocation;
import ai.neargo.shop.inventory.entity.InvOutboundLine;
import ai.neargo.shop.inventory.entity.InvOutboundOrder;
import ai.neargo.shop.inventory.entity.InvOutbox;
import ai.neargo.shop.inventory.entity.InvOwner;
import ai.neargo.shop.inventory.entity.InvReservation;
import ai.neargo.shop.inventory.entity.InvReservationLine;
import ai.neargo.shop.inventory.entity.InvStockBalance;
import ai.neargo.shop.inventory.entity.InvStockCount;
import ai.neargo.shop.inventory.entity.InvStockCountLine;
import ai.neargo.shop.inventory.entity.InvTransferOrder;
import ai.neargo.shop.inventory.entity.InvUom;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 进销存域的 Mapper 集合（形状与 {@code SettleMappers} / {@code ProductMappers} 一致：
 * 一域一个 final class，里面是嵌套接口）。
 *
 * <p><b>它们绑的是另一个 SqlSessionFactory</b>（{@code invSqlSessionFactory}）——
 * 平台的全局 {@code @MapperScan} 已把 {@code ai.neargo.shop.inventory} 排除。
 * 两头夹，少一头 {@code inv_*} 的查询就会打到平台库上。
 */
public final class InventoryMappers {

    private InventoryMappers() {
    }

    // ── 主数据 ────────────────────────────────────────────────────────────
    public interface OwnerMapper extends BaseMapper<InvOwner> {
    }

    /** 库位。门店 / 仓 / 在途 / 虚拟都在这张表里 —— 分成两个概念，调拨就没地方落脚。 */
    public interface LocationMapper extends BaseMapper<InvLocation> {
    }

    /** 计量单位字典。**全局，不带 owner** —— 单位是物理量，不因业主而异。 */
    public interface UomMapper extends BaseMapper<InvUom> {
    }

    public interface ItemMapper extends BaseMapper<InvItem> {
    }

    /** 外部引用。一个物料可有多个条码，但**一个条码不能指向两个物料**（唯一键管这条）。 */
    public interface ItemRefMapper extends BaseMapper<InvItemRef> {
    }

    // ── 余额：全库唯一允许并发条件更新的对象 ──────────────────────────────
    public interface BalanceMapper extends BaseMapper<InvStockBalance> {

        /**
         * 实存增减。**返回 0 = 会扣成负数**，调用方据此抛 {@code INV_INSUFFICIENT}。
         *
         * <p>靠 SQL 的条件更新做原子，不是「先查后改」—— 后者在并发下必然超卖：
         * 两个请求都查到「还有 1 件」。与平台 {@code SkuMapper.lockStock} 完全同一手法。
         */
        @Update("""
                UPDATE inv_stock_balance
                   SET on_hand = on_hand + #{delta}, last_moved_at = NOW(), version = version + 1
                 WHERE owner_id = #{ownerId} AND item_id = #{itemId} AND location_id = #{locationId}
                   AND on_hand + #{delta} >= 0
                """)
        int applyDelta(@Param("ownerId") String ownerId, @Param("itemId") String itemId,
                       @Param("locationId") String locationId, @Param("delta") int delta);

        /**
         * 预留：**只动 reserved，不动 on_hand**（不变式 I5）。
         * 守卫条件是 {@code available - qty >= 0} —— 没付钱的单不该把实存扣掉，
         * 但也不该让别人买到同一件货。
         */
        @Update("""
                UPDATE inv_stock_balance
                   SET reserved = reserved + #{qty}, version = version + 1
                 WHERE owner_id = #{ownerId} AND item_id = #{itemId} AND location_id = #{locationId}
                   AND on_hand - reserved - #{qty} >= 0
                """)
        int hold(@Param("ownerId") String ownerId, @Param("itemId") String itemId,
                 @Param("locationId") String locationId, @Param("qty") int qty);

        /**
         * 释放预留。**不加 {@code reserved >= qty} 的守卫也不会出错** ——
         * 释放只作用于 HELD 状态的预留行，重复释放在上层就被挡住了（与平台 release 同一手法）。
         * 这里仍然加上，是因为「reserved 变负」是一种无法自愈的脏数据。
         */
        @Update("""
                UPDATE inv_stock_balance
                   SET reserved = reserved - #{qty}, version = version + 1
                 WHERE owner_id = #{ownerId} AND item_id = #{itemId} AND location_id = #{locationId}
                   AND reserved - #{qty} >= 0
                """)
        int unhold(@Param("ownerId") String ownerId, @Param("itemId") String itemId,
                   @Param("locationId") String locationId, @Param("qty") int qty);
    }

    // ── 流水：只追加 ──────────────────────────────────────────────────────
    /**
     * <b>只有 insert 与 select</b>。BaseMapper 带来的 {@code updateById} / {@code deleteById}
     * 在这里是**不允许调用**的 —— 表上没有 {@code updated_at}，实体上没有 setter，
     * 而「写权守卫」会拦住任何试图 update 它的代码。
     */
    public interface LedgerMapper extends BaseMapper<InvLedger> {
    }

    // ── 预留 ──────────────────────────────────────────────────────────────
    public interface ReservationMapper extends BaseMapper<InvReservation> {
    }

    public interface ReservationLineMapper extends BaseMapper<InvReservationLine> {
    }

    // ── 单据 ──────────────────────────────────────────────────────────────
    public interface InboundOrderMapper extends BaseMapper<InvInboundOrder> {
    }

    public interface InboundLineMapper extends BaseMapper<InvInboundLine> {
    }

    public interface OutboundOrderMapper extends BaseMapper<InvOutboundOrder> {
    }

    public interface OutboundLineMapper extends BaseMapper<InvOutboundLine> {
    }

    public interface StockCountMapper extends BaseMapper<InvStockCount> {
    }

    public interface StockCountLineMapper extends BaseMapper<InvStockCountLine> {
    }

    public interface TransferOrderMapper extends BaseMapper<InvTransferOrder> {
    }

    // ── 事件出站 ──────────────────────────────────────────────────────────
    /** 独立库用不了平台的 {@code sys_outbox}，自己带一份。投递侧将来换 MQ，写入侧不动。 */
    public interface OutboxMapper extends BaseMapper<InvOutbox> {
    }
}
