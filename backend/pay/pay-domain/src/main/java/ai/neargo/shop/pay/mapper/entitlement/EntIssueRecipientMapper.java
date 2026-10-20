package ai.neargo.shop.pay.mapper.entitlement;

import ai.neargo.shop.pay.entitlement.entity.EntIssueRecipient;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** Global policy locks precede recipient updates so personal limits and aggregate counters share a transaction. */
public interface EntIssueRecipientMapper extends BaseMapper<EntIssueRecipient> {
    @Insert("INSERT INTO ent_issue_recipient (tenant_no,template_no,user_no,held_count,issued_count,"
            + "version,deleted,created_at,updated_at,created_by,updated_by) "
            + "VALUES (#{tenant},#{no},#{user},0,0,0,0,#{at},#{at},#{actor},#{actor}) ON DUPLICATE KEY UPDATE id=id")
    int ensure(@Param("tenant") String tenant, @Param("no") String no, @Param("user") String user,
               @Param("actor") String actor, @Param("at") LocalDateTime at);

    /** A current read must not inherit a repeatable-read snapshot opened by an earlier ownership lookup. */
    @Select("SELECT * FROM ent_issue_recipient WHERE tenant_no=#{tenant} AND template_no=#{no} "
            + "AND deleted=0 AND held_count+issued_count>#{limit} LIMIT 1 FOR UPDATE")
    EntIssueRecipient findExceeding(@Param("tenant") String tenant, @Param("no") String no,
                                   @Param("limit") int limit);

    @Update("UPDATE ent_issue_recipient SET held_count=held_count+#{quantity},version=version+1,"
            + "updated_by=#{actor},updated_at=#{at} WHERE tenant_no=#{tenant} AND template_no=#{no} "
            + "AND user_no=#{user} AND deleted=0 AND #{quantity}<=#{limit}-held_count-issued_count")
    int reserve(@Param("tenant") String tenant, @Param("no") String no, @Param("user") String user,
                @Param("quantity") int quantity, @Param("limit") int limit,
                @Param("actor") String actor, @Param("at") LocalDateTime at);

    @Update("UPDATE ent_issue_recipient SET held_count=held_count-#{quantity},issued_count=issued_count+#{issued},"
            + "version=version+1,updated_by=#{actor},updated_at=#{at} WHERE tenant_no=#{tenant} "
            + "AND template_no=#{no} AND user_no=#{user} AND deleted=0 AND held_count>=#{quantity}")
    int settle(@Param("tenant") String tenant, @Param("no") String no, @Param("user") String user,
               @Param("quantity") int quantity, @Param("issued") int issued,
               @Param("actor") String actor, @Param("at") LocalDateTime at);
}
