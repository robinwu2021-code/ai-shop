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
    @Select("SELECT * FROM pts_lifecycle_fact WHERE type_code=#{type} AND sub_order_no=#{sub}")
    Map<String, Object> lifecycle(@Param("type") String type, @Param("sub") String sub);
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
