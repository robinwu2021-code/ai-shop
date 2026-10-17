package ai.neargo.shop.trade.order.mapper;

import org.apache.ibatis.annotations.*;

import java.util.Map;

/** All rows belong to the source transaction manager, never payTxManager. */
@Mapper
public interface OrderPointsBindingMapper {
    @Select("SELECT * FROM ord_points_binding WHERE sub_order_no=#{sub}")
    Map<String, Object> binding(String sub);

    @Select(
            "SELECT * FROM ord_points_binding WHERE request_id=#{request} OR sub_order_no=#{sub}"
                + " FOR UPDATE")
    java.util.List<Map<String, Object>> existingBinding(
            @Param("request") String request, @Param("sub") String sub);

    @Select("SELECT * FROM ord_points_binding WHERE binding_no=#{no} FOR UPDATE")
    Map<String, Object> lockBinding(String no);

    @Insert(
            "INSERT INTO"
                + " ord_points_binding(binding_no,request_id,order_no,sub_order_no,store_no,phase,payload_json)"
                + " VALUES(#{no},#{request},#{order},#{sub},#{store},'PENDING',#{body})")
    int createBinding(
            @Param("no") String no,
            @Param("request") String request,
            @Param("order") String order,
            @Param("sub") String sub,
            @Param("store") String store,
            @Param("body") String body);

    @Update(
            "UPDATE ord_points_binding SET"
                + " phase=#{phase},payload_json=#{body},last_transition_json=#{command},version=version+1,updated_at=CURRENT_TIMESTAMP"
                + " WHERE binding_no=#{no} AND version=#{version}")
    int changeBinding(
            @Param("no") String no,
            @Param("version") long version,
            @Param("phase") String phase,
            @Param("body") String body,
            @Param("command") String command);

    @Select("SELECT COUNT(*) FROM ord_points_binding WHERE store_no=#{store} AND phase<>'CLOSED'")
    long openBindings(String store);

    @Select(
            "SELECT * FROM ord_points_integration_policy WHERE store_no=#{store} AND"
                    + " scene=#{scene}")
    Map<String, Object> policy(@Param("store") String store, @Param("scene") String scene);

    @Select(
            "SELECT * FROM ord_points_integration_policy WHERE store_no=#{store} AND scene=#{scene}"
                    + " FOR UPDATE")
    Map<String, Object> lockPolicy(@Param("store") String store, @Param("scene") String scene);

    @Insert(
            "INSERT INTO ord_points_integration_policy(store_no,scene,mode,version)"
                    + " VALUES(#{store},#{scene},'DETACHED',-1) ON DUPLICATE KEY UPDATE"
                    + " store_no=store_no")
    int initializePolicy(@Param("store") String store, @Param("scene") String scene);

    @Update(
            "UPDATE ord_points_integration_policy SET"
                + " mode=#{mode},payload_json=#{body},version=version+1,updated_at=CURRENT_TIMESTAMP"
                + " WHERE store_no=#{store} AND scene=#{scene} AND version=#{version}")
    int changePolicy(
            @Param("store") String store,
            @Param("scene") String scene,
            @Param("version") long version,
            @Param("mode") String mode,
            @Param("body") String body);

    @Select("SELECT * FROM ord_points_command WHERE request_id=#{id} FOR UPDATE")
    Map<String, Object> command(String id);

    @Insert(
            "INSERT INTO ord_points_command(request_id,command_json,result_json)"
                    + " VALUES(#{id},#{body},#{result})")
    int saveCommand(
            @Param("id") String id, @Param("body") String body, @Param("result") String result);
}
