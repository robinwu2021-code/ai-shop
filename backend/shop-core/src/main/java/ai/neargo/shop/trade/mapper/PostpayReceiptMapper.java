package ai.neargo.shop.trade.mapper;

import ai.neargo.shop.trade.entity.OrdIndustryOrderSource;
import ai.neargo.shop.trade.entity.OrdOfflineRecord;
import ai.neargo.shop.trade.entity.OrdOrder;
import ai.neargo.shop.trade.entity.OrdPostpayReceipt;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 行业后付人工实收的持久化（TDD-餐饮柜台后付结账）。加锁读与带守卫的状态推进都在这里，服务里不拼 SQL。 */
public interface PostpayReceiptMapper extends BaseMapper<OrdPostpayReceipt> {

    @Select("SELECT * FROM ord_postpay_receipt WHERE tenant_no='MAIN' AND entity_no=#{entity} AND"
            + " store_no=#{store} AND biz_no=#{biz} AND deleted=0")
    OrdPostpayReceipt findByBiz(@Param("entity") String entity, @Param("store") String store,
            @Param("biz") String biz);

    @Select("SELECT order_no FROM ord_postpay_receipt_root WHERE tenant_no='MAIN' AND receipt_no=#{receipt}"
            + " ORDER BY id")
    List<String> rootOrders(@Param("receipt") String receipt);

    /** 锁住结算单覆盖的核心单，顺序固定（按单号），避免两个确认交叉加锁。 */
    @Select("<script>SELECT * FROM ord_order WHERE tenant_no='MAIN' AND deleted=0 AND order_no IN"
            + "<foreach collection='orders' item='o' open='(' separator=',' close=')'>#{o}</foreach>"
            + " ORDER BY order_no FOR UPDATE</script>")
    List<OrdOrder> lockOrders(@Param("orders") List<String> orders);

    /** 行业来源：后付模式与所属门店以来源绑定为准。 */
    @Select("<script>SELECT order_no, order_mode, entity_no, store_no FROM ord_industry_order_source"
            + " WHERE tenant_no='MAIN' AND order_no IN"
            + "<foreach collection='orders' item='o' open='(' separator=',' close=')'>#{o}</foreach></script>")
    List<OrdIndustryOrderSource> sources(@Param("orders") List<String> orders);

    @Select("<script>SELECT * FROM ord_offline_record WHERE tenant_no='MAIN' AND deleted=0 AND record_no IN"
            + "<foreach collection='records' item='r' open='(' separator=',' close=')'>#{r}</foreach>"
            + " ORDER BY record_no FOR UPDATE</script>")
    List<OrdOfflineRecord> lockRecords(@Param("records") List<String> records);

    @Select("<script>SELECT record_no FROM ord_postpay_receipt_record WHERE tenant_no='MAIN' AND record_no IN"
            + "<foreach collection='records' item='r' open='(' separator=',' close=')'>#{r}</foreach></script>")
    List<String> usedRecords(@Param("records") List<String> records);

    /** 这张核心单是否已被后付人工实收覆盖（order_no 唯一，结果只会是 0 或 1）。 */
    @Select("SELECT COUNT(*) FROM ord_postpay_receipt_root WHERE tenant_no=#{tenant} AND order_no=#{order}")
    int countRoot(@Param("tenant") String tenantNo, @Param("order") String orderNo);

    @Insert("INSERT INTO ord_postpay_receipt_root(tenant_no,receipt_no,order_no,due_minor,core_version,created_at)"
            + " VALUES ('MAIN',#{receipt},#{order},#{due},#{version},CURRENT_TIMESTAMP(3))")
    int insertRoot(@Param("receipt") String receipt, @Param("order") String order, @Param("due") long due,
            @Param("version") String version);

    @Insert("INSERT INTO ord_postpay_receipt_record(tenant_no,receipt_no,record_no,method,amount_minor,created_at)"
            + " VALUES ('MAIN',#{receipt},#{record},#{method},#{amount},CURRENT_TIMESTAMP(3))")
    int insertRecord(@Param("receipt") String receipt, @Param("record") String record, @Param("method") String method,
            @Param("amount") long amount);

    /**
     * 后付核心单推到已付（与商家「确认线下收款」同一写法：只写状态、付款时间与线下确认人、确认时间；来源记在实收表）。守卫：还是读到时的版本、仍待支付、普通来源、没有线上支付渠道与流水、没有在途的线上支付请求
     * 与支付决议 —— 任何一条不满足就更新 0 行，调用方整笔回滚。
     */
    @Update("UPDATE ord_order o SET status='PAID',paid_at=#{paid},"
            + " offline_confirmed_by=#{actor},offline_confirmed_at=#{recorded},"
            + " updated_at=CURRENT_TIMESTAMP(3),version=version+1"
            + " WHERE o.tenant_no='MAIN' AND o.order_no=#{order} AND o.deleted=0 AND o.version=#{revision}"
            + " AND o.status='WAIT_PAY' AND o.admission_kind='LEGACY_FUNDED' AND o.receipt_provenance IS NULL"
            + " AND o.pay_channel IS NULL AND o.pay_trade_no IS NULL"
            + " AND NOT EXISTS (SELECT 1 FROM ord_checkout_payment_request p WHERE p.tenant_no=o.tenant_no"
            + " AND p.order_no=o.order_no AND p.deleted=0)"
            + " AND NOT EXISTS (SELECT 1 FROM ord_payment_resolution p WHERE p.tenant_no=o.tenant_no"
            + " AND p.pay_no=o.order_no AND p.deleted=0)")
    int markPaid(@Param("order") String order, @Param("revision") long revision, @Param("paid") long paidAt,
            @Param("actor") String actor, @Param("recorded") long recordedAt);
}
