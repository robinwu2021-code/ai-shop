package ai.neargo.shop.trade.mapper;

import ai.neargo.shop.common.config.TradeSqlMapper;
import ai.neargo.shop.trade.entity.OrdOfflineRecord;
import ai.neargo.shop.trade.entity.OrdStoreCollection;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.toolkit.Constants;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** Maps App-entered offline receipts to the single trade payment journal. */
public interface OfflinePaymentMapper extends TradeSqlMapper {
    String SCOPE = " source='OFFLINE' AND offline_request_hash IS NOT NULL AND deleted=0 ";
    String PROJECTION = """
            SELECT id,tenant_no,entity_no,store_no,record_no,amount_minor,currency,
                   CASE method WHEN 'TRANSFER' THEN 'BANK_TRANSFER' WHEN 'CARD' THEN 'EXTERNAL_POS'
                       ELSE method END AS method,
                   sub_order_no AS order_no,biz_no,note,received_at,corrected,recorded_by,
                   CASE status WHEN 'CONFIRMED' THEN 'RECORDED' WHEN 'CANCELLED' THEN 'VOIDED'
                       WHEN 'REVERSED' THEN 'REFUNDED' ELSE status END AS status,
                   offline_request_hash AS request_hash,offline_history,version,created_at
            FROM ord_payment WHERE
            """;
    String OWNED = " AND tenant_no=#{tenant} AND entity_no=#{entity} AND store_no=#{store} ";

    @Select(PROJECTION + SCOPE + OWNED + " AND request_key=#{key}")
    OrdOfflineRecord findKey(String tenant, String entity, String store, String key);

    @Select(PROJECTION + SCOPE + OWNED + " AND record_no=#{no} FOR UPDATE")
    OrdOfflineRecord lock(String tenant, String entity, String store, String no);

    @Select("SELECT * FROM (" + PROJECTION + SCOPE + ") receipts ${ew.customSqlSegment}")
    List<OrdOfflineRecord> list(@Param(Constants.WRAPPER) Wrapper<OrdOfflineRecord> filter);

    @Select("SELECT COUNT(*) FROM (" + PROJECTION + SCOPE + ") receipts ${ew.customSqlSegment}")
    long count(@Param(Constants.WRAPPER) Wrapper<OrdOfflineRecord> filter);

    @Select("SELECT COALESCE(SUM(amount_minor),0) FROM ord_payment WHERE" + SCOPE + OWNED
            + " AND status='CONFIRMED' AND received_at>=#{from} AND received_at<#{to}")
    long sumRecorded(String tenant, String entity, String store, LocalDateTime from, LocalDateTime to);

    @Select("SELECT COALESCE(SUM(amount_minor),0) FROM ord_payment WHERE" + SCOPE + OWNED
            + " AND status='CONFIRMED' AND sub_order_no=#{sub} AND currency=#{currency}")
    long sumRecordedOfOrder(String tenant, String entity, String store, String sub, String currency);

    @Insert("""
            INSERT INTO ord_payment(tenant_no,entity_no,store_no,record_no,amount_minor,currency,method,
                order_no,sub_order_no,biz_type,biz_no,note,received_at,status,source,purpose,corrected,recorded_by,
                request_key,offline_request_hash,offline_history,version,created_at)
            VALUES(#{row.tenantNo},#{row.entityNo},#{row.storeNo},#{row.recordNo},#{row.amountMinor},#{row.currency},
                CASE #{row.method} WHEN 'BANK_TRANSFER' THEN 'TRANSFER' WHEN 'EXTERNAL_POS' THEN 'CARD'
                    ELSE #{row.method} END,
                #{parentOrder},#{row.orderNo},IF(#{row.orderNo} IS NULL,NULL,'SUB_ORDER'),#{row.bizNo},#{row.note},
                #{row.receivedAt},'CONFIRMED','OFFLINE','PAY',0,#{row.recordedBy},#{key},#{row.requestHash},
                #{row.offlineHistory},#{row.version},#{row.createdAt})
            """)
    int insert(OrdOfflineRecord row, String parentOrder, String key);

    @Update("""
            UPDATE ord_payment SET amount_minor=#{row.amountMinor},
                method=CASE #{row.method} WHEN 'BANK_TRANSFER' THEN 'TRANSFER' WHEN 'EXTERNAL_POS' THEN 'CARD'
                    ELSE #{row.method} END, received_at=#{row.receivedAt},
                status=CASE #{row.status} WHEN 'RECORDED' THEN 'CONFIRMED' WHEN 'VOIDED' THEN 'CANCELLED'
                    WHEN 'REFUNDED' THEN 'REVERSED' ELSE #{row.status} END,
                corrected=#{row.corrected},offline_history=#{row.offlineHistory},version=version+1
            WHERE tenant_no=#{row.tenantNo} AND entity_no=#{row.entityNo} AND store_no=#{row.storeNo}
                AND record_no=#{row.recordNo} AND version=#{row.version}
            AND
            """ + SCOPE)
    int update(@Param("row") OrdOfflineRecord row);

    @Insert("""
            INSERT INTO ord_payment(tenant_no,entity_no,store_no,record_no,amount_minor,currency,method,
                order_no,sub_order_no,biz_type,biz_no,received_at,status,source,purpose,corrects_record_no,recorded_by)
            SELECT tenant_no,entity_no,store_no,#{no},-amount_minor,currency,method,
                order_no,sub_order_no,biz_type,biz_no,#{at},'CONFIRMED','OFFLINE','PAY',record_no,#{actor}
            FROM ord_payment WHERE tenant_no=#{row.tenantNo} AND entity_no=#{row.entityNo} AND store_no=#{row.storeNo}
                AND record_no=#{row.recordNo} AND status='REVERSED' AND
            """ + SCOPE)
    int insertReversal(OrdOfflineRecord row, String no, LocalDateTime at, String actor);

    @Select("""
            SELECT amount_minor AS paid_minor,currency,pay_channel FROM ord_payment
            WHERE tenant_no='MAIN' AND sub_order_no=#{sub} AND source='STORE_COLLECT'
                AND status='CONFIRMED' AND deleted=0
            """)
    List<OrdStoreCollection> collections(String sub);

    @Select("""
            SELECT COUNT(*) FROM ord_payment
            WHERE tenant_no='MAIN' AND pay_order_no=#{no} AND source='STORE_COLLECT' AND deleted=0
            """)
    long countCollection(String no);

    @Insert("""
            INSERT INTO ord_payment(tenant_no,entity_no,store_no,record_no,amount_minor,currency,order_no,
                sub_order_no,biz_type,biz_no,source,purpose,status,pay_order_no,pay_channel)
            VALUES('MAIN',#{row.entityNo},#{row.storeNo},#{recordNo},#{row.paidMinor},#{row.currency},
                #{row.orderNo},#{row.subOrderNo},'SUB_ORDER',#{row.subOrderNo},'STORE_COLLECT','PAY','CONFIRMED',
                #{row.payOrderNo},#{row.payChannel})
            """)
    int insertCollection(OrdStoreCollection row, String recordNo);
}
