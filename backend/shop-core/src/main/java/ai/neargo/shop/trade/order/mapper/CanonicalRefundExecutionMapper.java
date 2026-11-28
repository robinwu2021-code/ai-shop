package ai.neargo.shop.trade.order.mapper;

import ai.neargo.shop.common.config.TradeSqlMapper;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface CanonicalRefundExecutionMapper extends TradeSqlMapper {
    String CASH_ROW = " FROM ord_refund_obligation WHERE kind='CASH' AND execution_no LIKE 'RF-%'";

    @Select("SELECT *" + CASH_ROW + " AND execution_no=#{no}")
    Map<String, Object> find(@Param("no") String executionNo);

    @Select("SELECT *" + CASH_ROW + " AND execution_no=#{no} FOR UPDATE")
    Map<String, Object> lock(@Param("no") String executionNo);

    @Select("SELECT *" + CASH_ROW + " AND next_attempt_at<=#{now} AND next_attempt_at<9223372036854775807"
            + " ORDER BY id LIMIT #{limit}")
    List<Map<String, Object>> due(@Param("now") long now, @Param("limit") int limit);

    @Select("SELECT *" + CASH_ROW + " AND sub_order_no=#{sub} AND state='SUCCEEDED'"
            + " AND finished_at<=#{through} ORDER BY finished_at,id")
    List<Map<String, Object>> succeeded(@Param("sub") String subOrderNo, @Param("through") long through);

    @Insert("""
            INSERT INTO ord_refund_obligation
                (execution_no, after_sale_no, sub_order_no, kind, source_no, currency, amount_minor,
                 payload_json, input_hash, state, result_json, authority_proof, error_code,
                 next_attempt_at, finished_at)
            VALUES
                (#{row.executionNo}, #{row.afterSaleNo}, #{row.subOrderNo}, 'CASH', #{row.sourceNo},
                 #{row.currency}, #{row.amountMinor}, #{row.payload}, #{row.hash}, #{row.state},
                 #{row.result}, #{row.proof}, #{row.error}, #{row.nextAttemptAt}, #{row.finishedAt})
            """)
    int insert(@Param("row") CashRow row);

    @Update("""
            UPDATE ord_refund_obligation SET
                state=#{row.state}, result_json=#{row.result}, authority_proof=#{row.proof},
                error_code=#{row.error}, next_attempt_at=#{row.nextAttemptAt}, finished_at=#{row.finishedAt}
            WHERE id=#{id} AND kind='CASH' AND input_hash=#{row.hash} AND result_json=#{expected}
            """)
    int update(@Param("id") long id, @Param("expected") String expected, @Param("row") CashRow row);

    @Select(RefundExecutionMapper.LOCK_SUB)
    OrdSubOrder lockSub(@Param("sub") String subOrderNo);

    @Select(RefundExecutionMapper.FUNDED_SOURCE)
    int fundedSource(@Param("order") String order, @Param("sub") String subOrder,
            @Param("as") String afterSale, @Param("tenant") String tenant, @Param("entity") String entity);

    record CashRow(String executionNo, String afterSaleNo, String subOrderNo, String sourceNo,
                   String currency, Long amountMinor, String payload, String hash, String state,
                   String result, String proof, String error, long nextAttemptAt, Long finishedAt) {}
}
