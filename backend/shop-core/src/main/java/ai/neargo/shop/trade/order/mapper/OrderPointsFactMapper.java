package ai.neargo.shop.trade.order.mapper;

import org.apache.ibatis.annotations.*;

import java.util.*;

@Mapper
public interface OrderPointsFactMapper {
    @Insert(
            "INSERT INTO ord_points_source_counter(type_code,last_sequence) VALUES(#{type},0) ON"
                    + " DUPLICATE KEY UPDATE type_code=type_code")
    int initialize(String type);

    @Select(
            "SELECT last_sequence FROM ord_points_source_counter WHERE type_code=#{type} FOR"
                    + " UPDATE")
    long lockCounter(String type);

    @Update(
            "UPDATE ord_points_source_counter SET last_sequence=#{sequence} WHERE"
                    + " type_code=#{type}")
    int advance(@Param("type") String type, @Param("sequence") long sequence);

    @Select("SELECT * FROM ord_points_source_fact WHERE event_no=#{event}")
    Map<String, Object> find(String event);

    @Insert(
            "INSERT INTO"
                + " ord_points_source_fact(event_no,type_code,source_sequence,store_no,sub_order_no,schema_version,payload_json,normalized_hash,terminal_source)"
                + " VALUES(#{event},#{type},#{sequence},#{store},#{sub},1,#{body},#{hash},#{terminal})")
    int insert(
            @Param("event") String event,
            @Param("type") String type,
            @Param("sequence") long sequence,
            @Param("store") String store,
            @Param("sub") String sub,
            @Param("body") String body,
            @Param("hash") String hash,
            @Param("terminal") boolean terminal);

    @Select(
            "SELECT payload_json FROM ord_points_source_fact WHERE type_code=#{type} AND"
                    + " source_sequence>#{after} ORDER BY source_sequence LIMIT #{limit}")
    List<String> scan(
            @Param("type") String type, @Param("after") long after, @Param("limit") int limit);

    @Update(
            "UPDATE ord_points_source_fact SET delivered=1,delivered_at=CURRENT_TIMESTAMP WHERE"
                    + " event_no=#{event}")
    int acknowledge(String event);

    @Select("SELECT COUNT(*) FROM ord_points_source_fact WHERE sub_order_no=#{sub} AND delivered=0")
    long pendingSub(String sub);

    @Select(
            "SELECT COUNT(*) FROM ord_points_source_fact WHERE sub_order_no=#{sub} AND"
                    + " terminal_source=1 AND delivered=1")
    long terminalSub(String sub);

    @Select("SELECT COUNT(*) FROM ord_points_source_fact WHERE store_no=#{store} AND delivered=0")
    long pendingStore(String store);

    @Select(
            "SELECT COUNT(*) FROM ord_points_binding b WHERE b.store_no=#{store} AND NOT"
                    + " EXISTS(SELECT 1 FROM ord_points_source_fact f WHERE"
                    + " f.sub_order_no=b.sub_order_no AND f.terminal_source=1 AND f.delivered=1)")
    long unclosedSource(String store);

    @Select(
            "SELECT event_no FROM ord_points_source_fact WHERE delivered=0 ORDER BY"
                    + " created_at,type_code,source_sequence LIMIT #{limit}")
    List<String> pending(int limit);
}
