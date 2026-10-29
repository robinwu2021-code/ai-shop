package ai.neargo.shop.inventory.mapper;

import ai.neargo.shop.inventory.entity.InvDailySnapshot;
import ai.neargo.shop.inventory.entity.InvExecutionRetention;
import ai.neargo.shop.inventory.entity.InvExecutionRetentionClaim;
import ai.neargo.shop.inventory.entity.InvInboundLine;
import ai.neargo.shop.inventory.entity.InvInboundOrder;
import ai.neargo.shop.inventory.entity.InvItem;
import ai.neargo.shop.inventory.entity.InvItemRef;
import ai.neargo.shop.inventory.entity.InvLedger;
import ai.neargo.shop.inventory.entity.InvLocation;
import ai.neargo.shop.inventory.entity.InvOpenCredential;
import ai.neargo.shop.inventory.entity.InvOutboundLine;
import ai.neargo.shop.inventory.entity.InvOutboundOrder;
import ai.neargo.shop.inventory.entity.InvOutbox;
import ai.neargo.shop.inventory.entity.InvOwner;
import ai.neargo.shop.inventory.entity.InvReservation;
import ai.neargo.shop.inventory.entity.InvReservationLine;
import ai.neargo.shop.inventory.entity.InvStockBalance;
import ai.neargo.shop.inventory.entity.InvStockCount;
import ai.neargo.shop.inventory.entity.InvStockCountLine;
import ai.neargo.shop.inventory.entity.InvStockCountOperation;
import ai.neargo.shop.inventory.entity.InvSupplier;
import ai.neargo.shop.inventory.entity.InvTransferOrder;
import ai.neargo.shop.inventory.entity.InvUom;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.math.BigDecimal;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

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
                    + " on_hand+#{delta}>=reserved")
        int applyExactDelta(
                @Param("ownerId") String ownerId,
                @Param("itemId") String itemId,
                @Param("locationId") String locationId,
                @Param("delta") BigDecimal delta);

        /**
         * 实存增减。**返回 0 = 会扣成负数**，调用方据此抛 {@code INV_INSUFFICIENT}。
         *
         * <p>靠 SQL 的条件更新做原子，不是「先查后改」—— 后者在并发下必然超卖： 两个请求都查到「还有 1 件」。与平台 {@code SkuMapper.lockStock}
         * 完全同一手法。
         */
        @Update(
                """
                UPDATE inv_stock_balance
                   SET on_hand = on_hand + #{delta}, last_moved_at = NOW(), version = version + 1
                 WHERE owner_id = #{ownerId} AND item_id = #{itemId} AND location_id = #{locationId}
                   AND on_hand + #{delta} >= 0
                   AND (#{delta} >= 0 OR on_hand + #{delta} >= reserved)
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
                   AND on_hand - reserved - #{qty} >= 0
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
    }

    // ── 流水：只追加 ──────────────────────────────────────────────────────
    /**
     * <b>只有 insert 与 select</b>。BaseMapper 带来的 {@code updateById} / {@code deleteById}
     * 在这里是**不允许调用**的 —— 表上没有 {@code updated_at}，实体上没有 setter， 而「写权守卫」会拦住任何试图 update 它的代码。
     */
    public interface LedgerMapper extends BaseMapper<InvLedger> {}

    // ── 预留 ──────────────────────────────────────────────────────────────
    public interface ReservationMapper extends BaseMapper<InvReservation> {
        // This index is the canonical physical HEAD lock order. ORDER BY alone can
        // filesort after locking in primary-key scan order. Keep this a current read:
        // plain discovery could omit heads created after a caller's earlier RR snapshot.
        @Select(
                value = "SELECT * FROM inv_reservation FORCE INDEX (uk_reservation)"
                    + " WHERE external_ref=#{order} ORDER BY owner_id,reservation_id FOR UPDATE",
                databaseId = "MySQL")
        @Select(
                "SELECT * FROM inv_reservation WHERE external_ref=#{order} ORDER BY"
                    + " owner_id,reservation_id FOR UPDATE")
        List<InvReservation> lockOriginal(@Param("order") String order);

        // Current locking join: immutable identity discovery must not inherit an older RR
        // read view. Drive canonical HEAD order, taking each LINE only after its own HEAD.
        // Complete this scan before any stock/progress mutation; do not add DISTINCT.
        @Select(
                value = "SELECT r.* FROM inv_reservation r FORCE INDEX (uk_reservation)"
                    + " STRAIGHT_JOIN inv_reservation_line l FORCE INDEX (uk_res_line)"
                    + " ON l.reservation_id=r.reservation_id AND l.owner_id=r.owner_id"
                    + " WHERE l.fulfillment_ref=#{fulfillment} ORDER BY r.owner_id,r.reservation_id FOR UPDATE",
                databaseId = "MySQL")
        @Select(
                "SELECT r.* FROM inv_reservation r JOIN inv_reservation_line l"
                    + " ON l.reservation_id=r.reservation_id AND l.owner_id=r.owner_id"
                    + " WHERE l.fulfillment_ref=#{fulfillment} ORDER BY r.owner_id,r.reservation_id FOR UPDATE")
        List<InvReservation> lockByFulfillment(@Param("fulfillment") String fulfillment);

    }

    public interface ReservationLineMapper extends BaseMapper<InvReservationLine> {
        @Select(
                "SELECT * FROM inv_reservation_line WHERE reservation_id=#{reservation} ORDER BY"
                    + " line_no FOR UPDATE")
        List<InvReservationLine> lockOriginal(@Param("reservation") String reservation);
    }

    /** Current permanent links, including terminal rows, serialize with their real reservation parents. */
    public interface ExecutionRetentionMapper extends BaseMapper<InvExecutionRetention> {
        @Select("SELECT * FROM inv_execution_retention WHERE owner_id=#{owner} AND reservation_id=#{reservation}"
                + " ORDER BY original_line_id,id FOR UPDATE")
        List<InvExecutionRetention> lockReservation(@Param("owner") String owner,
                @Param("reservation") String reservation);

        @Select("SELECT * FROM inv_execution_retention WHERE component_no=#{component}"
                + " ORDER BY owner_id,reservation_id,original_line_id,id FOR UPDATE")
        List<InvExecutionRetention> lockComponent(@Param("component") String component);

        @Update("UPDATE inv_execution_retention SET issue_observation_json=#{observation},"
                + " issue_observation_hash=#{hash},retention_revision=retention_revision+1,updated_at=UTC_TIMESTAMP(3)"
                + " WHERE id=#{row.id} AND component_no=#{row.componentNo} AND request_hash=#{row.requestHash}"
                + " AND reference_hash=#{row.referenceHash} AND owner_id=#{row.ownerId}"
                + " AND reservation_id=#{row.reservationId} AND original_line_id=#{row.originalLineId}"
                + " AND state='RETAINED' AND retention_revision=#{row.retentionRevision}"
                + " AND retention_revision<9223372036854775807 AND issue_observation_hash IS NULL"
                + " AND issue_observation_json IS NULL")
        int recordIssued(@Param("row") InvExecutionRetention row, @Param("observation") String observation,
                @Param("hash") String hash);
    }

    /** Header permanently serializes an absent-claim seal against delayed first line insertion. */
    public interface ExecutionRetentionClaimMapper extends BaseMapper<InvExecutionRetentionClaim> {
        @Select("SELECT * FROM inv_execution_retention_claim WHERE component_no=#{component} FOR UPDATE")
        InvExecutionRetentionClaim lockComponent(@Param("component") String component);

        @Update("UPDATE inv_execution_retention_claim SET issue_observation_json=#{observation},"
                + " issue_observation_hash=#{hash},claim_revision=claim_revision+1,updated_at=UTC_TIMESTAMP(3)"
                + " WHERE id=#{row.id} AND component_no=#{row.componentNo} AND request_hash=#{row.requestHash}"
                + " AND reference_hash=#{row.referenceHash} AND claim_revision=#{row.claimRevision}"
                + " AND state='RETAINED' AND claim_revision<9223372036854775807"
                + " AND issue_observation_json IS NULL AND issue_observation_hash IS NULL")
        int recordIssued(@Param("row") InvExecutionRetentionClaim row, @Param("observation") String observation,
                @Param("hash") String hash);
    }

    // ── 单据 ──────────────────────────────────────────────────────────────
    public interface InboundOrderMapper extends BaseMapper<InvInboundOrder> {}

    public interface InboundLineMapper extends BaseMapper<InvInboundLine> {}

    public interface OutboundOrderMapper extends BaseMapper<InvOutboundOrder> {
        // Deliberately nonlocking discovery. An inherited RR snapshot may be stale or missing;
        // reverse/void must abort on unknown or changed association after current source-root locks.
        @Select("SELECT * FROM inv_outbound_order WHERE owner_id=#{owner} AND outbound_no=#{document}")
        InvOutboundOrder findReversalCandidate(@Param("owner") String owner, @Param("document") String document);
    }

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
}
