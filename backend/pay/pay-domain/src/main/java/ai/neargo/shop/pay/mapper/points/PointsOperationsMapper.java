package ai.neargo.shop.pay.mapper.points;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;

@Mapper
public interface PointsOperationsMapper {
    @Select(
            "<script>SELECT v.id,v.voucher_no,v.biz_ref,v.currency,v.rate_points,v.rate_minor,v.created_at FROM pts_voucher v WHERE v.type_code=#{type} AND v.id&gt;#{after} AND v.deleted=0 AND v.created_at&gt;=#{from} AND v.created_at&lt;#{to}<choose><when test='direction == \"EARN\"'> AND v.biz_ref LIKE 'GRANT:%'</when><when test='direction == \"REDEEM\"'> AND (v.biz_ref LIKE 'HOLD:%' OR v.biz_ref LIKE 'RELEASE:%' OR v.biz_ref LIKE 'FINALIZE:%')</when><otherwise> AND (v.biz_ref LIKE 'CANCEL:%' OR v.biz_ref LIKE 'REFUND_CLOSE:%' OR v.biz_ref LIKE 'REFUND_CONFIRM:%')</otherwise></choose><if test='store != null'> AND EXISTS (SELECT 1 FROM pts_ledger_entry e WHERE e.type_code=v.type_code AND e.voucher_no=v.voucher_no AND e.holder_type='STORE' AND e.holder_no=#{store} AND e.deleted=0)</if><if test='user != null'> AND EXISTS (SELECT 1 FROM pts_ledger_entry e WHERE e.type_code=v.type_code AND e.voucher_no=v.voucher_no AND e.holder_type='USER' AND e.holder_no=#{user} AND e.deleted=0)</if> ORDER BY v.id LIMIT #{limit}</script>")
    List<Map<String, Object>> transactionVouchers(
            @Param("type") String type,
            @Param("direction") String direction,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("store") String store,
            @Param("user") String user,
            @Param("after") long after,
            @Param("limit") int limit);

    @Select("SELECT id,voucher_no,biz_ref,currency,rate_points,rate_minor,created_at FROM pts_voucher WHERE type_code=#{type} AND voucher_no=#{voucher} AND deleted=0")
    Map<String, Object> voucherByNo(@Param("type") String type, @Param("voucher") String voucher);

    @Select(
            "<script>SELECT id,type_code,holder_type,holder_no,issued_points,issuable_points,pending_points,available_points,held_points,refund_reserved_points,recirculatable_points,frozen,expire_at,expiry_revision,last_active_at,version FROM pts_account WHERE type_code=#{type} AND holder_type=#{holder}<if test='holderNo != null'> AND holder_no=#{holderNo}</if> AND id&gt;#{after} AND deleted=0 ORDER BY id LIMIT #{limit}</script>")
    List<Map<String, Object>> accounts(
            @Param("type") String type,
            @Param("holder") ai.neargo.shop.pay.points.model.HolderType holder,
            @Param("holderNo") String holderNo,
            @Param("after") long after,
            @Param("limit") int limit);

    @Select(
            "SELECT version FROM pts_account WHERE type_code=#{type} AND holder_type=#{holder} AND"
                + " holder_no=#{subject} AND deleted=0")
    Map<String, Object> accountHead(
            @Param("type") String type,
            @Param("holder") ai.neargo.shop.pay.points.model.HolderType holder,
            @Param("subject") String subject);

    @Select(
            "<script>SELECT"
                + " id,entry_no,voucher_no,holder_type,holder_no,bucket,delta_points,after_points,created_at"
                + " FROM pts_ledger_entry WHERE type_code=#{type} AND deleted=0 AND"
                + " id>#{after}<choose><when test='voucher != null'> AND"
                + " voucher_no=#{voucher}</when><otherwise> AND holder_type=#{holder} AND"
                + " holder_no=#{subject}</otherwise></choose> ORDER BY id LIMIT #{limit}</script>")
    List<Map<String, Object>> entries(
            @Param("type") String type,
            @Param("holder") ai.neargo.shop.pay.points.model.HolderType holder,
            @Param("subject") String subject,
            @Param("voucher") String voucher,
            @Param("after") long after,
            @Param("limit") int limit);

    @Select(
            "SELECT id,payload_json,version FROM pts_store_statement WHERE type_code=#{type} AND"
                    + " store_no=#{store} AND id>#{after} AND deleted=0 ORDER BY id LIMIT #{limit}")
    List<Map<String, Object>> statements(
            @Param("type") String type,
            @Param("store") String store,
            @Param("after") long after,
            @Param("limit") int limit);

    @Select(
            "SELECT id,type_code,state,event_no,version FROM pts_outbox WHERE id=#{id} AND"
                    + " deleted=0 FOR UPDATE")
    Map<String, Object> lockEvent(long id);

    @Select(
            "SELECT id,payload_json FROM pts_switch WHERE (type_code=#{type} OR type_code='*') AND"
                    + " scope<>'FREEZE' AND id>#{after} AND deleted=0 ORDER BY id LIMIT #{limit}")
    List<Map<String, Object>> switches(
            @Param("type") String type, @Param("after") long after, @Param("limit") int limit);

    @Select(
            "SELECT id,state,version,payload_json FROM pts_merchant_agreement WHERE"
                + " type_code=#{type} AND store_no=#{store} AND id>#{after} AND deleted=0 ORDER BY"
                + " id LIMIT #{limit}")
    List<Map<String, Object>> agreements(
            @Param("type") String type,
            @Param("store") String store,
            @Param("after") long after,
            @Param("limit") int limit);

    @Select(
            "SELECT"
                + " id,receivable_no,store_no,state,original_minor,recovered_minor,locked_minor,version"
                + " FROM pts_receivable WHERE type_code=#{type} AND store_no=#{store} AND"
                + " id>#{after} AND deleted=0 ORDER BY id LIMIT #{limit}")
    List<Map<String, Object>> receivables(
            @Param("type") String type,
            @Param("store") String store,
            @Param("after") long after,
            @Param("limit") int limit);

    @Select(
            "SELECT COALESCE(SUM(original_minor-recovered_minor),0) FROM pts_receivable"
                    + " WHERE type_code=#{type} AND deleted=0")
    long outstandingReceivable(@Param("type") String type);

    @Select(
            "SELECT COUNT(*) FROM pts_outbox WHERE state='MANUAL' AND deleted=0 AND"
                    + " (#{type,jdbcType=VARCHAR} IS NULL OR type_code=#{type,jdbcType=VARCHAR})")
    long manualEvents(@Param("type") String type);

    @Select(
            "SELECT * FROM pts_topup_plan WHERE plan_no=#{plan} AND period_key=#{period} AND"
                    + " deleted=0")
    Map<String, Object> plan(@Param("plan") String plan, @Param("period") String period);

    @Select(
            "SELECT * FROM pts_topup_plan WHERE plan_no=#{plan} AND period_key=#{period} AND"
                    + " deleted=0 FOR UPDATE")
    Map<String, Object> lockPlan(@Param("plan") String plan, @Param("period") String period);

    @Select(
            "<script>SELECT * FROM pts_topup_plan WHERE type_code=#{type} AND id>#{after} AND"
                    + " deleted=0<choose><when test='configs'> AND period_key='CONFIG' AND"
                    + " state='ACTIVE'</when><otherwise> AND state='SUBMITTED'</otherwise></choose>"
                    + " ORDER BY id LIMIT #{limit}</script>")
    List<Map<String, Object>> plans(
            @Param("type") String type,
            @Param("after") long after,
            @Param("limit") int limit,
            @Param("configs") boolean configs);

    @Insert(
            "INSERT INTO pts_topup_plan(type_code,plan_no,period_key,state,payload_json,created_by)"
                    + " VALUES(#{type},#{plan},#{period},#{state},#{body},'POINTS_PLAN')")
    int insertPlan(
            @Param("type") String type,
            @Param("plan") String plan,
            @Param("period") String period,
            @Param("state") String state,
            @Param("body") String body);

    @Update(
            "UPDATE pts_topup_plan SET"
                + " state=#{state},payload_json=#{body},version=version+1,updated_at=CURRENT_TIMESTAMP,updated_by='POINTS_PLAN'"
                + " WHERE plan_no=#{plan} AND period_key=#{period} AND type_code=#{type} AND"
                + " version=#{version} AND deleted=0")
    int updatePlan(
            @Param("type") String type,
            @Param("plan") String plan,
            @Param("period") String period,
            @Param("state") String state,
            @Param("body") String body,
            @Param("version") long version);

    @Select(
            "<script>SELECT * FROM ${table} WHERE type_code=#{type} AND id>#{after} AND deleted=0"
                + " AND state IN<foreach collection='states' item='state' open='(' separator=','"
                + " close=')'>#{state}</foreach> ORDER BY id LIMIT #{limit}</script>")
    List<Map<String, Object>> pending(
            @Param("table") String table,
            @Param("type") String type,
            @Param("states") List<String> states,
            @Param("after") long after,
            @Param("limit") int limit);

    @Select(
            "SELECT * FROM pts_account WHERE type_code=#{type} AND holder_type='USER' AND"
                + " available_points>0 AND expire_at IS NOT NULL AND frozen=0 AND id>#{after} AND"
                + " deleted=0 ORDER BY id LIMIT #{limit}")
    List<Map<String, Object>> lifecycleAccounts(
            @Param("type") String type, @Param("after") long after, @Param("limit") int limit);

    // table is exclusively supplied by the closed PointsOperationsStore.Kind enum.
    @Select(
            "SELECT * FROM ${table} WHERE type_code=#{type} AND id>#{after} AND deleted=0 ORDER BY"
                    + " id LIMIT #{limit}")
    List<Map<String, Object>> page(
            @Param("table") String table,
            @Param("type") String type,
            @Param("after") long after,
            @Param("limit") int limit);

    @Select(
            "SELECT * FROM pts_voucher WHERE type_code=#{type} AND id>#{after} AND deleted=0"
                    + " AND created_at>=#{from} AND created_at<#{to} ORDER BY id LIMIT #{limit}")
    List<Map<String, Object>> vouchers(
            @Param("type") String type,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("after") long after,
            @Param("limit") int limit);

    @Select(
            "SELECT t.* FROM ${table} t JOIN pts_voucher v ON t.voucher_no=v.voucher_no WHERE"
                    + " t.type_code=#{type} AND t.id>#{after} AND t.deleted=0 AND v.deleted=0 AND"
                    + " v.created_at>=#{from} AND v.created_at<#{to} ORDER BY t.id LIMIT #{limit}")
    List<Map<String, Object>> dayRows(
            @Param("table") String table,
            @Param("type") String type,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("after") long after,
            @Param("limit") int limit);

    @Select(
            "SELECT o.id,o.version,v.created_at AS voucher_at FROM pts_outbox o JOIN pts_voucher v"
                + " ON o.event_no=CONCAT('POINTS:',v.voucher_no) AND o.type_code=v.type_code WHERE"
                + " o.type_code=#{type} AND o.state='NEW' AND o.deleted=0 AND v.deleted=0 ORDER BY"
                + " o.id LIMIT #{limit}")
    List<Map<String, Object>> postingEvents(@Param("type") String type, @Param("limit") int limit);

    @Update(
            "UPDATE pts_outbox SET state='DONE',version=version+1 WHERE id=#{id} AND"
                    + " version=#{version} AND state='NEW'")
    int projected(@Param("id") long id, @Param("version") long version);

    @Select(
            "SELECT COALESCE(SUM(e.external_minor),0) FROM pts_fund_evidence e JOIN pts_voucher v"
                + " ON e.used_voucher_no=v.voucher_no WHERE v.type_code=#{type} AND e.verified=1"
                + " AND e.deleted=0 AND v.deleted=0")
    long evidenceTotal(String type);

    @Select(
            "SELECT * FROM pts_stat_daily WHERE type_code=#{type} AND day_key=#{day} AND"
                    + " scope_no=#{scope} AND deleted=0")
    Map<String, Object> stat(
            @Param("type") String type, @Param("day") String day, @Param("scope") String scope);

    @Insert(
            "INSERT INTO pts_stat_daily(type_code,state,payload_json,day_key,scope_no)"
                    + " VALUES(#{type},#{state},#{body},#{day},#{scope}) ON DUPLICATE KEY UPDATE"
                    + " state=VALUES(state),payload_json=VALUES(payload_json),version=version+1")
    int saveStat(
            @Param("type") String type,
            @Param("day") String day,
            @Param("scope") String scope,
            @Param("state") String state,
            @Param("body") String body);

    @Delete(
            "DELETE FROM pts_stat_daily WHERE type_code=#{type} AND day_key=#{day} AND"
                    + " state='STATS'")
    int clearStats(@Param("type") String type, @Param("day") String day);

    @Select(
            "SELECT DISTINCT sub_order_no FROM pts_redeem_position WHERE state='HELD' AND"
                    + " sub_order_no IS NOT NULL AND created_at<#{cutoff} AND deleted=0 ORDER BY"
                    + " sub_order_no LIMIT #{limit}")
    List<String> heldSubOrders(@Param("cutoff") LocalDateTime cutoff, @Param("limit") int limit);

    @Select(
            "<script>SELECT sub_order_no FROM pts_grant_position WHERE deleted=0 AND sub_order_no"
                    + " IN<foreach collection='subs' item='sub' open='(' separator=','"
                    + " close=')'>#{sub}</foreach></script>")
    List<String> grantedSubOrders(@Param("subs") Collection<String> subs);
}
