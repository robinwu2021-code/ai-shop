package ai.neargo.shop.trade.mapper;

import ai.neargo.shop.trade.entity.OrdPricingTemplate;
import ai.neargo.shop.trade.entity.OrdIndustryOrderSource;
import ai.neargo.shop.trade.entity.OrdPricingFundingSource;
import ai.neargo.shop.trade.entity.OrdPriceQuote;
import ai.neargo.shop.trade.entity.OrdPriceQuoteLine;
import ai.neargo.shop.trade.entity.OrdPriceQuoteAllocation;
import ai.neargo.shop.trade.entity.OrdPricingTemplateRevision;
import ai.neargo.shop.trade.entity.OrdStorePricingConfig;
import ai.neargo.shop.trade.entity.OrdStorePricingRevision;
import ai.neargo.shop.trade.entity.OrdAfterSale;
import ai.neargo.shop.trade.entity.OrdExpressPickup;
import ai.neargo.shop.trade.entity.OrdInvoiceRequest;
import ai.neargo.shop.trade.entity.OrdItem;
import ai.neargo.shop.trade.entity.OrdOrder;
import ai.neargo.shop.trade.entity.OrdStatusLog;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.entity.SysSequence;
import ai.neargo.shop.trade.entity.TrdCartItem;
import ai.neargo.shop.trade.entitlement.entity.OrdEntAuthorization;
import ai.neargo.shop.trade.entitlement.entity.OrdEntSourceDecision;
import ai.neargo.shop.trade.entitlement.entity.OrdCardSaleSnapshot;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** trade 域的 Mapper 集合。 */
public final class TradeMappers {

    private TradeMappers() {
    }

    public interface IndustryOrderSourceMapper extends BaseMapper<OrdIndustryOrderSource> { }

    public interface PricingFundingSourceMapper extends BaseMapper<OrdPricingFundingSource> { }

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

    public interface EntitlementAuthorizationMapper extends BaseMapper<OrdEntAuthorization> {
    }

    public interface EntitlementSourceDecisionMapper extends BaseMapper<OrdEntSourceDecision> {
    }

    public interface CardSaleSnapshotMapper extends BaseMapper<OrdCardSaleSnapshot> {
        @Select("SELECT DISTINCT s.order_no FROM ord_card_sale_snapshot s JOIN ord_order o "
                + "ON o.tenant_no=s.tenant_no AND o.order_no=s.order_no WHERE s.tenant_no=#{tenant} "
                + "AND s.deleted=0 AND o.deleted=0 AND s.delivery_status='PENDING' "
                + "AND o.status IN ('PAID','CANCELLED','CLOSED') AND s.order_no > #{after} "
                + "ORDER BY s.order_no LIMIT #{limit}")
        java.util.List<String> pendingDelivery(@Param("tenant") String tenant,@Param("after") String after,
                                               @Param("limit") int limit);
        @Update("UPDATE ord_card_sale_snapshot SET delivery_status='ACKNOWLEDGED',version=version+1,"
                + "updated_at=#{at},updated_by=#{actor} WHERE tenant_no=#{tenant} AND order_no=#{no} "
                + "AND deleted=0 AND delivery_status='PENDING' AND EXISTS (SELECT 1 FROM ord_order o "
                + "WHERE o.tenant_no=#{tenant} AND o.order_no=#{no} AND o.deleted=0 "
                + "AND o.status IN ('PAID','CANCELLED','CLOSED'))")
        int acknowledgeDelivery(@Param("tenant") String tenant,@Param("no") String no,
                @Param("actor") String actor,@Param("at") java.time.LocalDateTime at);

        @Insert("INSERT IGNORE INTO ord_card_sale_snapshot (tenant_no,snapshot_no,source_key,order_no,sub_order_no,order_line_no,unit_index,entitlement_index,user_no,entity_no,store_no,goods_no,sku_no,template_version_no,unit_price_minor,currency,rule_snapshot,snapshot_hash,paid_fact_no,paid_at,version,deleted,created_at,updated_at,created_by,updated_by) VALUES (#{tenantNo},#{snapshotNo},#{sourceKey},#{orderNo},#{subOrderNo},#{orderLineNo},#{unitIndex},#{entitlementIndex},#{userNo},#{entityNo},#{storeNo},#{goodsNo},#{skuNo},#{templateVersionNo},#{unitPriceMinor},#{currency},#{ruleSnapshot},#{snapshotHash},NULL,NULL,0,0,#{createdAt},#{updatedAt},#{createdBy},#{updatedBy})")
        int insertIfAbsent(OrdCardSaleSnapshot row);

        @Update("UPDATE ord_card_sale_snapshot SET paid_fact_no=#{paidFactNo},paid_at=#{paidAt},updated_at=#{updatedAt},updated_by=#{updatedBy},version=version+1 WHERE tenant_no=#{tenantNo} AND order_no=#{orderNo} AND paid_fact_no IS NULL AND deleted=0")
        int markPaidOnce(@Param("tenantNo") String tenantNo, @Param("orderNo") String orderNo,
                         @Param("paidFactNo") String paidFactNo, @Param("paidAt") java.time.LocalDateTime paidAt,
                         @Param("updatedAt") java.time.LocalDateTime updatedAt, @Param("updatedBy") String updatedBy);

        @Select("SELECT * FROM ord_card_sale_snapshot WHERE tenant_no=#{tenantNo} AND source_key=#{sourceKey} AND deleted=0 LIMIT 1")
        OrdCardSaleSnapshot findBySource(@Param("tenantNo") String tenantNo, @Param("sourceKey") String sourceKey);
    }

    public interface StatusLogMapper extends BaseMapper<OrdStatusLog> {
    }

    public interface AfterSaleMapper extends BaseMapper<OrdAfterSale> {
    }

    public interface InvoiceRequestMapper extends BaseMapper<OrdInvoiceRequest> {
    }

    /** 微信发货信息录入的上报台账（V323）。见 {@link ai.neargo.shop.trade.entity.TrdShippingUpload} */
    public interface ShippingUploadMapper
            extends com.baomidou.mybatisplus.core.mapper.BaseMapper<
                    ai.neargo.shop.trade.entity.TrdShippingUpload> {
    }

    public interface ExpressPickupMapper extends BaseMapper<OrdExpressPickup> {
    }

    /**
     * 号段序列（ADR-035）。三个方法合起来是一次「领段」：touch 兜底种子、bump 原子推进、peek 读回。
     * 调用方必须在**同一事务**里 bump → peek（行锁保证读到的是自己 bump 后的值），见 SeqSegmentSource。
     */
    public interface SeqMapper extends BaseMapper<SysSequence> {

        /** 种子兜底：迁移已插则无副作用。INSERT IGNORE 两端（MySQL/H2）都认 */
        @Insert("INSERT IGNORE INTO sys_sequence(seq_name, next_value) VALUES(#{name}, 0)")
        int touch(@Param("name") String name);

        /** 原子推进 step（单条 UPDATE 带行锁） */
        @Update("UPDATE sys_sequence SET next_value = next_value + #{step} WHERE seq_name = #{name}")
        int bump(@Param("name") String name, @Param("step") long step);

        /** 读回当前游标（必须与 bump 同事务，否则读到的可能是别人 bump 后的值） */
        @Select("SELECT next_value FROM sys_sequence WHERE seq_name = #{name}")
        Long peek(@Param("name") String name);
    }
    public interface PriceQuoteMapper extends BaseMapper<OrdPriceQuote> { }
    public interface PriceQuoteLineMapper extends BaseMapper<OrdPriceQuoteLine> { }
    public interface PriceQuoteAllocationMapper extends BaseMapper<OrdPriceQuoteAllocation> { }

    public interface PricingTemplateMapper extends BaseMapper<OrdPricingTemplate> { }
    public interface PricingTemplateRevisionMapper extends BaseMapper<OrdPricingTemplateRevision> { }
    public interface StorePricingConfigMapper extends BaseMapper<OrdStorePricingConfig> { }
    public interface StorePricingRevisionMapper extends BaseMapper<OrdStorePricingRevision> { }

}
