package ai.neargo.shop.pay.mapper.entitlement;
import ai.neargo.shop.pay.entitlement.entity.PayEntExceptionAction;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Param;
public interface PayEntExceptionActionMapper extends BaseMapper<PayEntExceptionAction> {
    @Select("SELECT * FROM pay_ent_exception_action WHERE tenant_no=#{tenant} AND request_key=#{key} "
            + "AND deleted=0 FOR UPDATE")
    PayEntExceptionAction lock(@Param("tenant") String tenant,@Param("key") String key);
    @Insert("INSERT INTO pay_ent_exception_action(tenant_no,action_no,exception_no,operation_kind,request_key,payload_hash,reason,"
            + "entity_no,actor_no,status,version,deleted,created_at,updated_at,created_by,updated_by) VALUES "
            + "(#{tenantNo},#{actionNo},#{exceptionNo},#{operationKind},#{requestKey},#{payloadHash},#{reason},#{entityNo},#{actorNo},"
            + "'PENDING',0,0,#{createdAt},#{updatedAt},#{createdBy},#{updatedBy}) ON DUPLICATE KEY UPDATE id=id")
    int claim(PayEntExceptionAction action);
}
