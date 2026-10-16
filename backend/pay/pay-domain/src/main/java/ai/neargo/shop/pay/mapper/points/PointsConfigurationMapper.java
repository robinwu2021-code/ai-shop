package ai.neargo.shop.pay.mapper.points;

import ai.neargo.shop.pay.points.model.PointType;
import ai.neargo.shop.pay.points.model.PoolRequest;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/** Only metadata changes and zero-balance initialization; no money or account increment SQL lives here. */
public interface PointsConfigurationMapper {
    @Select("SELECT * FROM pts_point_type WHERE type_code=#{type}")
    Map<String, Object> type(String type);
    @Select("SELECT * FROM pts_point_type WHERE active_market=#{market} AND status IN ('ACTIVE','DRAINING')")
    Map<String, Object> activeType(String market);
    @Select("SELECT * FROM pts_point_type ORDER BY id")
    List<Map<String, Object>> types();
    @Select("SELECT * FROM pts_fund_pool WHERE type_code=#{type}")
    Map<String, Object> pool(String type);
    @Select("SELECT * FROM pts_account WHERE type_code=#{type} AND holder_type=#{holder} AND holder_no=#{no}")
    Map<String, Object> account(@Param("type") String type, @Param("holder") String holder, @Param("no") String no);
    @Select("SELECT * FROM pts_voucher WHERE biz_ref=#{biz}")
    Map<String, Object> voucher(String biz);
    @Select("SELECT * FROM ${table} WHERE position_no=#{no}")
    Map<String, Object> position(@Param("table") String table, @Param("no") String no);
    @Select("SELECT * FROM pts_refund_preparation WHERE position_no=#{no} AND deleted=0 FOR UPDATE")
    Map<String, Object> lockRefundPreparation(String no);
    @Select("SELECT * FROM pts_payout_ticket WHERE position_no=#{no} AND deleted=0 FOR UPDATE")
    Map<String, Object> lockPayoutTicket(String no);
    @Select("SELECT * FROM pts_store_statement WHERE position_no=#{no} AND deleted=0 FOR UPDATE")
    Map<String, Object> lockStatement(String no);
    @Select("SELECT * FROM pts_receivable WHERE receivable_no=#{no} AND deleted=0 FOR UPDATE")
    Map<String, Object> lockReceivable(String no);
    @Select("SELECT * FROM pts_receivable WHERE type_code=#{type} AND store_no=#{store} AND state='OPEN'"
            + " AND deleted=0 AND recovered_minor+locked_minor<original_minor ORDER BY receivable_no FOR UPDATE")
    List<Map<String, Object>> statementDebts(@Param("type") String type, @Param("store") String store);
    @Select("SELECT * FROM pts_redeem_position WHERE position_no=#{no} AND deleted=0 FOR UPDATE")
    Map<String, Object> lockStatementSource(String no);
    @Select("SELECT * FROM pts_redeem_position WHERE type_code=#{type} AND store_no=#{store} AND deleted=0"
            + " AND state='SETTLEABLE' AND statement_no IS NULL AND original_points>processed_points"
            + " ORDER BY position_no FOR UPDATE")
    List<Map<String, Object>> statementCandidates(@Param("type") String type, @Param("store") String store);
    @Select("SELECT * FROM pts_receivable WHERE receivable_no=#{no} AND deleted=0")
    Map<String,Object> receivable(String no);
    @Select("SELECT * FROM pts_refund_reserve WHERE reserve_no=#{no}")
    Map<String,Object> reserve(String no);
    @Select("SELECT * FROM pts_lifecycle_fact WHERE type_code=#{type} AND sub_order_no=#{sub}")
    Map<String, Object> lifecycle(@Param("type") String type, @Param("sub") String sub);
    @Select("SELECT * FROM pts_redeem_position WHERE sub_order_no=#{sub} AND deleted=0 ORDER BY type_code")
    List<Map<String,Object>> redemptionsForSub(String sub);
    @Select("SELECT * FROM pts_refund_preparation WHERE type_code=#{type} AND sub_order_no=#{sub}"
            + " AND state NOT IN ('CONFIRMED','ABORTED') AND deleted=0 ORDER BY position_no")
    List<Map<String,Object>> openRefunds(@Param("type") String type,@Param("sub") String sub);
    @Select("SELECT COUNT(*) FROM stl_payment WHERE after_sale_no=#{afterSale} AND direction='REFUND' AND deleted=0")
    long cashRefundCount(String afterSale);
    @Select("SELECT payment_no,direction,after_sale_no,sub_order_no,user_no,currency,amount_minor,status,"
            + "pay_channel,refund_verified_provider_no,refund_verified_at,refund_verified_minor,succeeded_at FROM stl_payment WHERE payment_no=#{no} AND deleted=0")
    Map<String,Object> cashRefund(String no);
    @Select("SELECT * FROM pts_inbox WHERE event_no=#{event}")
    Map<String,Object> inbox(String event);
    @Select("SELECT * FROM pts_inbox WHERE event_no=#{event} AND deleted=0 FOR UPDATE")
    Map<String,Object> lockInbox(String event);
    @Insert("INSERT INTO pts_inbox(type_code,state,payload_json,event_no,payload_hash)"
            + " VALUES(#{type},#{state},#{payload},#{event},#{hash})")
    int inboxInsert(@Param("type") String type,@Param("state") String state,@Param("payload") String payload,
                    @Param("event") String event,@Param("hash") String hash);
    @Insert("INSERT INTO pts_lifecycle_fact(type_code,state,payload_json,sub_order_no,source_version)"
            + " VALUES(#{type},#{state},#{payload},#{sub},#{sourceVersion})")
    int lifecycleInsert(@Param("state") String state,@Param("type") String type,@Param("sub") String sub,@Param("sourceVersion") long version,
                        @Param("payload") String payload);
    @Update("UPDATE pts_lifecycle_fact SET state=#{state},payload_json=#{payload},source_version=#{sourceVersion},"
            + "version=version+1 WHERE type_code=#{type} AND sub_order_no=#{sub} AND version=#{expected}")
    int lifecycleFinalize(@Param("state") String state,@Param("type") String type,@Param("sub") String sub,
                          @Param("sourceVersion") long version,@Param("payload") String payload,
                          @Param("expected") long expected);
    @Insert("INSERT INTO pts_point_type(type_code,market,currency,scale,rate_points,rate_minor,deduct_ratio_bp,"
            + "idle_days,status,created_by) VALUES(#{t.typeCode},#{t.market},#{t.currency},#{t.scale},"
            + "#{t.rate.pointsUnit},#{t.rate.minorUnit},#{t.deductRatioBp},#{t.idleDays},'DRAFT',#{actor})")
    int createType(@Param("t") PointType type, @Param("actor") String actor);
    @Insert("INSERT INTO pts_fund_pool(type_code,currency,created_by) VALUES(#{type},#{currency},#{actor})")
    int createPool(@Param("type") String type, @Param("currency") String currency, @Param("actor") String actor);
    @Select("SELECT type_code FROM pts_point_type WHERE type_code=#{type} FOR UPDATE")
    String lockType(String type);
    @Select("SELECT type_code FROM pts_fund_pool WHERE type_code=#{type} FOR UPDATE")
    String lockPool(String type);
    @Update("UPDATE pts_point_type SET status=#{next},active_market=#{market},updated_by=#{actor},updated_at=CURRENT_TIMESTAMP,version=version+1 "
            + "WHERE type_code=#{type} AND status=#{expected}")
    int status(@Param("type") String type, @Param("expected") String expected,
               @Param("next") String next, @Param("market") String activeMarket, @Param("actor") String actor);
    @Select("SELECT * FROM pts_merchant_agreement WHERE active_key=#{key}")
    Map<String,Object> agreement(String key);
    @Insert("INSERT INTO pts_merchant_agreement(type_code,agreement_no,store_no,active_key,state,payload_json,created_by)"
            + " VALUES(#{type},#{no},#{store},#{key},#{state},#{payload},#{actor})")
    int putAgreement(@Param("no") String no,@Param("type") String type,@Param("store") String store,
                     @Param("key") String key,@Param("state") String state,@Param("payload") String payload,
                     @Param("actor") String actor);
    @Update("UPDATE pts_merchant_agreement SET active_key=NULL,state='TERMINATED',updated_by=#{actor},"
            + "updated_at=CURRENT_TIMESTAMP,version=version+1 WHERE active_key=#{key}")
    int terminateAgreement(@Param("key") String key,@Param("actor") String actor);
    @Insert("INSERT INTO pts_pool_request(type_code,request_no,submitted_by,evidence_no,state,payload_json,created_by)"
            + " VALUES(#{r.typeCode},#{r.requestNo},#{r.submittedBy},#{r.evidenceNo},'SUBMITTED',#{json},#{r.submittedBy})")
    int submit(@Param("r") PoolRequest request, @Param("json") String json);
    @Select("SELECT * FROM pts_pool_request WHERE request_no=#{no} FOR UPDATE")
    Map<String, Object> lockRequest(String no);
    @Update("UPDATE pts_pool_request SET approved_by=#{actor},state='APPROVING',version=version+1 "
            + "WHERE request_no=#{no} AND state='SUBMITTED' AND submitted_by<>#{actor}")
    int beginApproval(@Param("no") String no, @Param("actor") String actor);
    @Update("UPDATE pts_pool_request SET state='APPROVED',payload_json=#{json},version=version+1 "
            + "WHERE request_no=#{no} AND state='APPROVING'")
    int approve(@Param("no") String no, @Param("json") String json);
}
