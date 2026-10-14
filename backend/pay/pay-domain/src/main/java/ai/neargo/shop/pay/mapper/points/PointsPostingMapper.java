package ai.neargo.shop.pay.mapper.points;

import ai.neargo.shop.pay.points.model.AccountKey;
import ai.neargo.shop.pay.points.model.PositionMove;
import ai.neargo.shop.pay.points.model.ReserveMove;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/** Bound by the existing pay mapper scan; dynamic identifiers come only from closed enums. */
public interface PointsPostingMapper {
    @Insert("INSERT INTO pts_voucher(voucher_no,biz_ref,payload_hash,type_code,currency,rate_points,rate_minor,"
            + "result_json) VALUES(#{no},#{biz},#{hash},#{type},#{currency},#{points},#{minor},#{result})")
    int claim(@Param("no") String no, @Param("biz") String biz, @Param("hash") String hash,
              @Param("type") String type, @Param("currency") String currency, @Param("points") long points,
              @Param("minor") long minor, @Param("result") String result);

    @Select("SELECT * FROM pts_voucher WHERE biz_ref=#{biz} FOR UPDATE")
    Map<String, Object> voucher(String biz);

    @Select("SELECT * FROM pts_point_type WHERE type_code=#{type} FOR UPDATE")
    Map<String, Object> lockType(String type);

    @Select("SELECT * FROM pts_fund_pool WHERE type_code=#{type} FOR UPDATE")
    Map<String, Object> lockPool(String type);

    @Insert("INSERT INTO pts_account(type_code,holder_type,holder_no) "
            + "VALUES(#{key.typeCode},#{key.holderType},#{key.holderNo})")
    int createAccount(@Param("key") AccountKey key);

    @Select("SELECT * FROM pts_account WHERE type_code=#{key.typeCode} AND holder_type=#{key.holderType} "
            + "AND holder_no=#{key.holderNo} FOR UPDATE")
    Map<String, Object> lockAccount(@Param("key") AccountKey key);

    @Update("UPDATE pts_account SET ${column}=${column}+#{delta},version=version+1 "
            + "WHERE type_code=#{key.typeCode} AND holder_type=#{key.holderType} AND holder_no=#{key.holderNo} "
            + "AND ((holder_type='MINT' AND ${column}+#{delta}<=0) "
            + "OR (holder_type<>'MINT' AND ${column}+#{delta}>=0))")
    int moveAccount(@Param("key") AccountKey key, @Param("column") String column, @Param("delta") long delta);

    @Update("UPDATE pts_fund_pool SET u_minor=u_minor+#{u},c_minor=c_minor+#{c},"
            + "d_minor=d_minor+#{d},b_minor=b_minor+#{b},reserved_b_minor=reserved_b_minor+#{reserved},"
            + "version=version+1 WHERE type_code=#{type} AND u_minor+#{u}>=0 AND c_minor+#{c}>=0 "
            + "AND d_minor+#{d}>=0 AND b_minor+#{b}>=0 AND reserved_b_minor+#{reserved}>=0 "
            + "AND b_minor+#{b}>=reserved_b_minor+#{reserved}")
    int movePool(@Param("type") String type, @Param("u") long u, @Param("c") long c,
                 @Param("d") long d, @Param("b") long b, @Param("reserved") long reserved);

    @Insert("INSERT INTO pts_fund_evidence(evidence_no,currency,external_minor,verified)"
            + " VALUES(#{no},#{currency},#{amount},1)")
    int registerEvidence(@Param("no") String no,@Param("currency") String currency,@Param("amount") long amount);
    @Select("SELECT * FROM pts_fund_evidence WHERE evidence_no=#{no} FOR UPDATE")
    Map<String,Object> evidence(String no);
    @Update("UPDATE pts_fund_evidence SET used_voucher_no=#{voucher} WHERE evidence_no=#{no} "
            + "AND currency=#{currency} AND external_minor=#{amount} AND verified=1 AND used_voucher_no IS NULL")
    int consumeEvidence(@Param("no") String no, @Param("currency") String currency,
                        @Param("amount") long amount, @Param("voucher") String voucher);

    @Insert("INSERT INTO pts_ledger_entry(entry_no,voucher_no,type_code,holder_type,holder_no,bucket,"
            + "delta_points,after_points) VALUES(#{no},#{voucher},#{key.typeCode},#{key.holderType},"
            + "#{key.holderNo},#{bucket},#{delta},#{after})")
    int ledger(@Param("no") String no, @Param("voucher") String voucher, @Param("key") AccountKey key,
               @Param("bucket") String bucket, @Param("delta") long delta, @Param("after") long after);

    @Insert("INSERT INTO pts_pool_flow(flow_no,voucher_no,type_code,bucket,delta_minor,after_minor) "
            + "VALUES(#{no},#{voucher},#{type},#{bucket},#{delta},#{after})")
    int flow(@Param("no") String no, @Param("voucher") String voucher, @Param("type") String type,
             @Param("bucket") String bucket, @Param("delta") long delta, @Param("after") long after);

    @Select("SELECT holder_type,SUM(issued_points+issuable_points+pending_points+available_points+held_points"
            + "+refund_reserved_points+recirculatable_points) AS points_total FROM pts_account "
            + "WHERE type_code=#{type} GROUP BY holder_type")
    List<Map<String, Object>> totals(String type);

    @Select("SELECT * FROM ${table} WHERE position_no=#{no} FOR UPDATE")
    Map<String, Object> lockPosition(@Param("table") String table, @Param("no") String no);

    @Insert("INSERT INTO ${table}(position_no,type_code,state,original_points,processed_points,payload_json) "
            + "VALUES(#{move.positionNo},#{type},#{move.fromState},#{move.originalPoints},0,#{move.payload})")
    int createPosition(@Param("table") String table, @Param("type") String type, @Param("move") PositionMove move);

    @Update("UPDATE ${table} SET state=#{move.nextState},processed_points=processed_points+#{move.processedDelta},"
            + "payload_json=#{move.payload},version=version+1 WHERE position_no=#{move.positionNo} "
            + "AND type_code=#{type} AND state=#{move.fromState} AND version=#{revision} "
            + "AND original_points=#{move.originalPoints} AND processed_points+#{move.processedDelta}>=0 "
            + "AND processed_points+#{move.processedDelta}<=original_points")
    int movePosition(@Param("table") String table, @Param("type") String type,
                     @Param("move") PositionMove move, @Param("revision") long revision);

    @Update("UPDATE ${table} SET sub_order_no=#{sub},user_no=#{user} WHERE position_no=#{no}")
    int indexOrderPosition(@Param("table") String table,@Param("no") String no,
                           @Param("sub") String sub,@Param("user") String user);
    @Update("UPDATE pts_payout_ticket SET provider_idem=#{idem},gross_minor=#{gross},offset_minor=#{offset},"
            + "cash_minor=#{cash},currency=#{currency} WHERE position_no=#{no}")
    int indexPayout(@Param("no") String no,@Param("idem") String idem,@Param("gross") long gross,
                    @Param("offset") long offset,@Param("cash") long cash,@Param("currency") String currency);
    @Update("UPDATE pts_account SET last_active_at=#{at},expire_at=#{expiry},expiry_revision=expiry_revision+1 "
            + "WHERE type_code=#{type} AND holder_type='USER' AND holder_no=#{user}")
    int renewUser(@Param("type") String type,@Param("user") String user,@Param("at") long at,
                  @Param("expiry") long expiry);

    @Insert("INSERT INTO pts_refund_reserve(reserve_no,position_no,type_code,original_minor) "
            + "VALUES(#{move.reserveNo},#{move.positionNo},#{type},#{move.originalMinor})")
    int createReserve(@Param("type") String type, @Param("move") ReserveMove move);

    @Update("UPDATE pts_refund_reserve SET remaining_minor=remaining_minor+#{move.remainingDelta},"
            + "consumed_minor=consumed_minor+#{move.consumedDelta},released_minor=released_minor+#{move.releasedDelta},"
            + "version=version+1 WHERE reserve_no=#{move.reserveNo} AND position_no=#{move.positionNo} "
            + "AND type_code=#{type} AND version=#{revision} AND original_minor=#{move.originalMinor} "
            + "AND remaining_minor+#{move.remainingDelta}>=0 AND consumed_minor+#{move.consumedDelta}>=0 "
            + "AND released_minor+#{move.releasedDelta}>=0 "
            + "AND remaining_minor+#{move.remainingDelta}+consumed_minor+#{move.consumedDelta}"
            + "+released_minor+#{move.releasedDelta}=original_minor")
    int moveReserve(@Param("type") String type, @Param("move") ReserveMove move, @Param("revision") long revision);

    @Select("SELECT COALESCE(SUM(remaining_minor),0) FROM pts_refund_reserve WHERE type_code=#{type}")
    long reserveTotal(String type);

    @Insert("INSERT INTO pts_outbox(type_code,event_no,state,payload_json) VALUES(#{type},#{no},'NEW',#{payload})")
    int outbox(@Param("type") String type, @Param("no") String no, @Param("payload") String payload);
}
