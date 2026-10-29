package ai.neargo.inventory.domain.mapper;

import ai.neargo.inventory.domain.entity.InvAudit;
import ai.neargo.inventory.domain.entity.InvBusinessProfile;
import ai.neargo.inventory.domain.entity.InvClientSystem;
import ai.neargo.inventory.domain.entity.InvCommand;
import ai.neargo.inventory.domain.entity.InvDailySnapshot;
import ai.neargo.inventory.domain.entity.InvInboundLine;
import ai.neargo.inventory.domain.entity.InvInboundOrder;
import ai.neargo.inventory.domain.entity.InvItem;
import ai.neargo.inventory.domain.entity.InvItemRef;
import ai.neargo.inventory.domain.entity.InvLedger;
import ai.neargo.inventory.domain.entity.InvLocation;
import ai.neargo.inventory.domain.entity.InvOpenCredential;
import ai.neargo.inventory.domain.entity.InvOutboundLine;
import ai.neargo.inventory.domain.entity.InvOutboundOrder;
import ai.neargo.inventory.domain.entity.InvOutbox;
import ai.neargo.inventory.domain.entity.InvOwner;
import ai.neargo.inventory.domain.entity.InvReservation;
import ai.neargo.inventory.domain.entity.InvReservationLine;
import ai.neargo.inventory.domain.entity.InvStockBalance;
import ai.neargo.inventory.domain.entity.InvStockHold;
import ai.neargo.inventory.domain.entity.InvTargetRef;
import ai.neargo.inventory.domain.entity.InvStockCount;
import ai.neargo.inventory.domain.entity.InvStockCountLine;
import ai.neargo.inventory.domain.entity.InvStockCountOperation;
import ai.neargo.inventory.domain.entity.InvSupplier;
import ai.neargo.inventory.domain.entity.InvTransferOrder;
import ai.neargo.inventory.domain.entity.InvUom;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;

/**
 * 进销存域的 Mapper 集合（形状与 {@code SettleMappers} / {@code ProductMappers} 一致： 一域一个 final class，里面是嵌套接口）。
 *
 * <p><b>它们绑的是另一个 SqlSessionFactory</b>（{@code invSqlSessionFactory}）—— 平台的全局 {@code @MapperScan} 已把
 * {@code ai.neargo.shop.inventory} 排除。 两头夹，少一头 {@code inv_*} 的查询就会打到平台库上。
 */
public final class InventoryMappers {

    private InventoryMappers() {}

    // ── 主数据 ────────────────────────────────────────────────────────────
    public interface OwnerMapper extends BaseMapper<InvOwner> {}

    /** 库位。门店 / 仓 / 在途 / 虚拟都在这张表里 —— 分成两个概念，调拨就没地方落脚。 */
    public interface LocationMapper extends BaseMapper<InvLocation> {}

    /** 计量单位字典。**全局，不带 owner** —— 单位是物理量，不因业主而异。 */
    public interface UomMapper extends BaseMapper<InvUom> {}

    public interface ItemMapper extends BaseMapper<InvItem> {}

    /** 供应商档案。**每个查询都要显式带 ownerId** —— 进销存不走平台 DataScope， 漏一处就是跨商家泄露，而它不会报错。 */
    public interface SupplierMapper extends BaseMapper<InvSupplier> {}

    /** 外部引用。一个物料可有多个条码，但**一个条码不能指向两个物料**（唯一键管这条）。 */
    public interface ItemRefMapper extends BaseMapper<InvItemRef> {}

    // ── 余额：全库唯一允许并发条件更新的对象 ──────────────────────────────
    public interface BalanceMapper extends BaseMapper<InvStockBalance> {
        @Update(
                "UPDATE inv_stock_balance SET"
                    + " on_hand=on_hand+#{delta},last_moved_at=NOW(),version=version+1 WHERE"
                    + " owner_id=#{ownerId} AND item_id=#{itemId} AND location_id=#{locationId} AND"
                    + " on_hand+#{delta}>=reserved+unavailable_quantity")
        int applyExactDelta(
                @Param("ownerId") String ownerId,
                @Param("itemId") String itemId,
                @Param("locationId") String locationId,
                @Param("delta") BigDecimal delta);

        /**
         * 实存增减。**返回 0 = 扣不动**，调用方据此抛 {@code STOCK_SHORT}。
         *
         * <p><b>扣减不许动别人的预留，也不许动冻结的货</b>（T2.5）：此前守卫只是 {@code on_hand + delta >= 0}，
         * 一张报废单能把已付款订单预留的货扣掉，而预留那边毫不知情 —— 到发货那天才发现没货。
         * 销售出库先释放预留、再过账，不受影响；入库（delta 为正）不设限。
         *
         * <p>靠 SQL 的条件更新做原子，不是「先查后改」—— 后者在并发下必然超卖： 两个请求都查到「还有 1 件」。与平台 {@code SkuMapper.lockStock}
         * 完全同一手法。
         */
        @Update(
                """
                UPDATE inv_stock_balance
                   SET on_hand = on_hand + #{delta}, last_moved_at = NOW(), version = version + 1
                 WHERE owner_id = #{ownerId} AND item_id = #{itemId} AND location_id = #{locationId}
                   AND (#{delta} >= 0 OR on_hand + #{delta} >= reserved + unavailable_quantity)
                """)
        int applyDelta(
                @Param("ownerId") String ownerId,
                @Param("itemId") String itemId,
                @Param("locationId") String locationId,
                @Param("delta") int delta);

        /**
         * 预留：**只动 reserved，不动 on_hand**（不变式 I5）。 守卫条件是 {@code available - qty >= 0} ——
         * 没付钱的单不该把实存扣掉， 但也不该让别人买到同一件货。
         */
        @Update(
                """
                UPDATE inv_stock_balance
                   SET reserved = reserved + #{qty}, version = version + 1
                 WHERE owner_id = #{ownerId} AND item_id = #{itemId} AND location_id = #{locationId}
                   AND on_hand - reserved - unavailable_quantity - #{qty} >= 0
                """)
        int hold(
                @Param("ownerId") String ownerId,
                @Param("itemId") String itemId,
                @Param("locationId") String locationId,
                @Param("qty") int qty);

        /**
         * 释放预留。**不加 {@code reserved >= qty} 的守卫也不会出错** —— 释放只作用于 HELD 状态的预留行，重复释放在上层就被挡住了（与平台
         * release 同一手法）。 这里仍然加上，是因为「reserved 变负」是一种无法自愈的脏数据。
         */
        @Update(
                """
                UPDATE inv_stock_balance
                   SET reserved = reserved - #{qty}, version = version + 1
                 WHERE owner_id = #{ownerId} AND item_id = #{itemId} AND location_id = #{locationId}
                   AND reserved - #{qty} >= 0
                """)
        int unhold(
                @Param("ownerId") String ownerId,
                @Param("itemId") String itemId,
                @Param("locationId") String locationId,
                @Param("qty") int qty);

        /**
         * 设本库位的安全库存覆盖。**返回 0 = 这件货在这个库位上没有余额行**， 调用方据此抛 {@code NOT_FOUND}。
         *
         * <p><b>为什么不用 {@code updateById}</b>：撤掉覆盖要写 null， 而 MyBatis-Plus 的 {@code updateById} 默认跳过
         * null 字段 —— 「撤掉」会变成一次什么都没做的成功，界面刷新后阈值还在。
         *
         * <p><b>刻意不动 {@code version}</b>：那一列是余额并发协议的一部分 （{@code applyDelta} / {@code hold}
         * 用它），而阈值不是数量， 跟着它涨会让「这行的数量被人改过」这个信号失真。 {@code updated_at} 由 DDL 的 {@code ON UPDATE} 自己刷。
         */
        @Update(
                """
                UPDATE inv_stock_balance
                   SET safety_stock = #{qty}, updated_by = #{operator}
                 WHERE owner_id = #{ownerId} AND item_id = #{itemId} AND location_id = #{locationId}
                """)
        int setSafetyStock(
                @Param("ownerId") String ownerId,
                @Param("itemId") String itemId,
                @Param("locationId") String locationId,
                @Param("qty") Integer qty,
                @Param("operator") String operator);

        /**
         * 冻结占用改成给定值（由冻结服务按「同一件至多计 1」重算后写入）。
         * <b>返回 0 = 可用量不够冻</b>：冻结不能把预留的货也冻进去。
         */
        @Update(
                """
                UPDATE inv_stock_balance
                   SET unavailable_quantity = #{unavailable}, version = version + 1
                 WHERE owner_id = #{ownerId} AND item_id = #{itemId} AND location_id = #{locationId}
                   AND on_hand - reserved >= #{unavailable}
                """)
        int setUnavailable(
                @Param("ownerId") String ownerId,
                @Param("itemId") String itemId,
                @Param("locationId") String locationId,
                @Param("unavailable") BigDecimal unavailable);
    }

    // ── 流水：只追加 ──────────────────────────────────────────────────────
    /**
     * <b>只有 insert 与 select</b>。BaseMapper 带来的 {@code updateById} / {@code deleteById}
     * 在这里是**不允许调用**的 —— 表上没有 {@code updated_at}，实体上没有 setter， 而「写权守卫」会拦住任何试图 update 它的代码。
     */
    public interface LedgerMapper extends BaseMapper<InvLedger> {}

    // ── 预留 ──────────────────────────────────────────────────────────────
    public interface ReservationMapper extends BaseMapper<InvReservation> {}

    public interface ReservationLineMapper extends BaseMapper<InvReservationLine> {}

    // ── 单据 ──────────────────────────────────────────────────────────────
    public interface InboundOrderMapper extends BaseMapper<InvInboundOrder> {}

    public interface InboundLineMapper extends BaseMapper<InvInboundLine> {}

    public interface OutboundOrderMapper extends BaseMapper<InvOutboundOrder> {}

    public interface OutboundLineMapper extends BaseMapper<InvOutboundLine> {}

    public interface StockCountMapper extends BaseMapper<InvStockCount> {}

    public interface StockCountLineMapper extends BaseMapper<InvStockCountLine> {}

    public interface StockCountOperationMapper extends BaseMapper<InvStockCountOperation> {}

    public interface TransferOrderMapper extends BaseMapper<InvTransferOrder> {}

    // ── 事件出站 ──────────────────────────────────────────────────────────
    /** 独立库用不了平台的 {@code sys_outbox}，自己带一份。投递侧将来换 MQ，写入侧不动。 */
    public interface OutboxMapper extends BaseMapper<InvOutbox> {}

    // ── 派生与对外 ────────────────────────────────────────────────────────
    /** 日快照。**派生数据**：删光重跑一遍就回来了，所以重跑是它的正常工作方式。 */
    public interface DailySnapshotMapper extends BaseMapper<InvDailySnapshot> {}

    /** Open API 凭证。{@code app_secret} 只存 bcrypt 哈希。 */
    public interface OpenCredentialMapper extends BaseMapper<InvOpenCredential> {}

    public interface ClientSystemMapper extends BaseMapper<InvClientSystem> {}

    public interface BusinessProfileMapper extends BaseMapper<InvBusinessProfile> {}

    public interface CommandMapper extends BaseMapper<InvCommand> {}

    public interface AuditMapper extends BaseMapper<InvAudit> {}

    public interface StockHoldMapper extends BaseMapper<InvStockHold> {}

    public interface TargetRefMapper extends BaseMapper<InvTargetRef> {}
}
