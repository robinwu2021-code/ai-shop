package ai.neargo.shop.pay.mapper.entitlement;

import ai.neargo.shop.pay.entitlement.entity.EntIssuePolicy;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** Conditioned writes keep issued plus held capacity within authorization under concurrent grants. */
public interface EntIssuePolicyMapper extends BaseMapper<EntIssuePolicy> {
    @Select("SELECT * FROM ent_issue_policy WHERE tenant_no=#{tenant} AND template_no=#{no} AND deleted=0 FOR UPDATE")
    EntIssuePolicy lock(@Param("tenant") String tenant, @Param("no") String no);

    @Update("UPDATE ent_issue_policy SET limit_count=#{row.limitCount},max_exposure_minor=#{row.maxExposureMinor},"
            + "per_user_limit=#{row.perUserLimit},enabled=#{row.enabled},updated_at=#{row.updatedAt},"
            + "updated_by=#{row.updatedBy},version=version+1 WHERE tenant_no=#{row.tenantNo} "
            + "AND template_no=#{row.templateNo} AND deleted=0 AND version=#{row.version} "
            + "AND (#{row.limitCount} IS NULL OR held_count+issued_count<=#{row.limitCount}) "
            + "AND (#{row.maxExposureMinor} IS NULL OR held_exposure_minor+used_exposure_minor"
            + "<=#{row.maxExposureMinor})")
    int configure(@Param("row") EntIssuePolicy row);

    @Update("UPDATE ent_issue_policy SET held_count=held_count+#{quantity},"
            + "held_exposure_minor=held_exposure_minor+#{amount},updated_by=#{actor},updated_at=#{at},version=version+1 "
            + "WHERE tenant_no=#{tenant} AND template_no=#{no} AND deleted=0 AND enabled=1 "
            + "AND (limit_count IS NULL OR #{quantity}<=limit_count-held_count-issued_count) "
            + "AND (max_exposure_minor IS NULL OR #{amount}<=max_exposure_minor-held_exposure_minor-used_exposure_minor)")
    int reserve(@Param("tenant") String tenant, @Param("no") String no, @Param("quantity") int quantity,
                @Param("amount") long amount, @Param("actor") String actor, @Param("at") LocalDateTime at);

    @Update("UPDATE ent_issue_policy SET held_count=held_count-#{quantity},"
            + "issued_count=issued_count+#{issued},held_exposure_minor=held_exposure_minor-#{amount},"
            + "used_exposure_minor=used_exposure_minor+#{used},updated_by=#{actor},updated_at=#{at},version=version+1 "
            + "WHERE tenant_no=#{tenant} AND template_no=#{no} AND deleted=0 "
            + "AND held_count>=#{quantity} AND held_exposure_minor>=#{amount}")
    int settle(@Param("tenant") String tenant, @Param("no") String no, @Param("quantity") int quantity,
               @Param("amount") long amount, @Param("issued") int issued, @Param("used") long used,
               @Param("actor") String actor, @Param("at") LocalDateTime at);
}
