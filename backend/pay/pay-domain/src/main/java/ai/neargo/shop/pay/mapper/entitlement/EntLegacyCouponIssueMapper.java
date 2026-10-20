package ai.neargo.shop.pay.mapper.entitlement;
import ai.neargo.shop.pay.migration.entity.EntLegacyCouponIssue;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;
public interface EntLegacyCouponIssueMapper extends BaseMapper<EntLegacyCouponIssue> {
    @Select("SELECT * FROM ent_legacy_coupon_issue WHERE tenant_no=#{tenant} AND entity_no=#{entity} AND issue_no=#{no} AND deleted=0 FOR UPDATE")
    @Options(flushCache=Options.FlushCachePolicy.TRUE,useCache=false)
    EntLegacyCouponIssue lock(@Param("tenant") String tenant,@Param("entity") String entity,@Param("no") String no);
}
