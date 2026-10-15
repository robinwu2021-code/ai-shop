package ai.neargo.shop.pay.mapper;

import ai.neargo.shop.pay.entity.StlBillRefund;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.Map;

/**
 * 结算单退款：流水 + 结算单累计字段的条件写（集群规则 R1 / R2）。
 *
 * <p>两条 UPDATE 都以「已退货款累计 + 状态」为 CAS 前值：份额是按前值算的，
 * 前值变了（并发的另一笔退款、分账任务改了状态）就写 0 行，调用方回滚重来，而不是在新前值上叠一笔旧份额。
 */
public interface BillRefundMapper extends BaseMapper<StlBillRefund> {

    @Select("SELECT COUNT(*) FROM stl_bill_refund WHERE after_sale_no = #{afterSaleNo}")
    long countByAfterSale(@Param("afterSaleNo") String afterSaleNo);

    /**
     * 分账前：各项金额按份额扣减，状态 old → next（未退完时 next = old）。
     * 参数逐项展开而不传 record —— MyBatis 取 record 字段依赖访问器约定，写错名只会在运行时报。
     */
    @Update("UPDATE stl_bill SET refunded_goods_minor = refunded_goods_minor + #{c.goods}, "
            + "refunded_freight_minor = refunded_freight_minor + #{freight}, "
            + "split_amount_minor = commission_minor + COALESCE(service_fee_minor, 0) "
            + "+ COALESCE(points_fee_minor, 0) - #{c.platform}, "
            + "gross_minor = gross_minor - #{c.gross}, "
            + "commission_minor = commission_minor - #{c.commission}, "
            + "service_fee_minor = COALESCE(service_fee_minor, 0) - #{c.serviceFee}, "
            + "points_fee_minor = COALESCE(points_fee_minor, 0) - #{c.pointsFee}, "
            + "subsidy_minor = COALESCE(subsidy_minor, 0) - #{c.subsidy}, "
            + "points_cost_minor = COALESCE(points_cost_minor, 0) - #{c.pointsCost}, "
            + "freight_income_minor = COALESCE(freight_income_minor, 0) - #{c.freight}, "
            + "net_minor = net_minor - #{c.merchant}, "
            + "status = #{next}, updated_at = CURRENT_TIMESTAMP, version = version + 1 "
            + "WHERE settle_no = #{settleNo} AND refunded_goods_minor = #{before} "
            + "AND refunded_freight_minor = #{freightBefore} AND status = #{old}")
    int adjustBeforeSplit(@Param("settleNo") String settleNo, @Param("c") Map<String, Long> cuts,
                          @Param("before") long before, @Param("freightBefore") long freightBefore,
                          @Param("freight") long freight, @Param("old") String old, @Param("next") String next);

    /** 分账后：累计已退货款与已回退额，状态 old → next。 */
    @Update("UPDATE stl_bill SET refunded_goods_minor = refunded_goods_minor + #{goods}, "
            + "refunded_freight_minor = refunded_freight_minor + #{freight}, "
            + "reversed_minor = reversed_minor + #{reversed}, "
            + "status = #{next}, updated_at = CURRENT_TIMESTAMP, version = version + 1 "
            + "WHERE settle_no = #{settleNo} AND refunded_goods_minor = #{before} "
            + "AND refunded_freight_minor = #{freightBefore} AND status = #{old}")
    int recordAfterSplit(@Param("settleNo") String settleNo, @Param("goods") long goods,
                         @Param("reversed") long reversed, @Param("before") long before,
                         @Param("freightBefore") long freightBefore, @Param("freight") long freight,
                         @Param("old") String old, @Param("next") String next);

    /** 通道回退失败：转人工（只在状态未被别人改过时）。 */
    @Update("UPDATE stl_bill SET status = 'MANUAL', last_error = #{error}, updated_at = CURRENT_TIMESTAMP, "
            + "version = version + 1 WHERE settle_no = #{settleNo} AND status = #{old}")
    int markManual(@Param("settleNo") String settleNo, @Param("old") String old, @Param("error") String error);
}
