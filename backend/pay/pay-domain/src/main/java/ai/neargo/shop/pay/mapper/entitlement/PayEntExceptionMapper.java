package ai.neargo.shop.pay.mapper.entitlement;
import ai.neargo.shop.pay.entitlement.entity.PayEntException;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Param;
public interface PayEntExceptionMapper extends BaseMapper<PayEntException> {
    @Select("SELECT * FROM pay_ent_exception WHERE tenant_no=#{tenant} AND exception_no=#{no} AND deleted=0 FOR UPDATE")
    PayEntException lock(@Param("tenant") String tenant,@Param("no") String no);
    @Insert("INSERT INTO pay_ent_exception(tenant_no,exception_no,entity_no,kind,biz_no,status,evidence,"
            + "version,deleted,created_at,updated_at,created_by,updated_by) VALUES(#{tenantNo},#{exceptionNo},"
            + "#{entityNo},#{kind},#{bizNo},'OPEN',#{evidence},0,0,#{createdAt},#{updatedAt},#{createdBy},#{updatedBy}) "
            + "ON DUPLICATE KEY UPDATE id=id")
    int claim(PayEntException row);
    @Select("SELECT * FROM pay_ent_exception WHERE tenant_no=#{tenant} AND kind='OUTBOX_DELIVERY' "
            + "AND biz_no=#{no} AND deleted=0")
    PayEntException findDelivery(@Param("tenant") String tenant,@Param("no") String no);
    @Select("SELECT * FROM pay_ent_exception WHERE tenant_no=#{tenant} AND kind='GRANT_DELIVERY' "
            + "AND biz_no=#{no} AND deleted=0")
    PayEntException findGrant(@Param("tenant") String tenant,@Param("no") String no);
}
