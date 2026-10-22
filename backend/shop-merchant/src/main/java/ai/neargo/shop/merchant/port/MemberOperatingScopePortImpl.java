package ai.neargo.shop.merchant.port;
import ai.neargo.shop.spi.member.MemberOperatingScopePort;import ai.neargo.shop.common.*;import org.springframework.jdbc.core.JdbcTemplate;import org.springframework.stereotype.Component;import java.util.*;
@Component
public class MemberOperatingScopePortImpl implements MemberOperatingScopePort {
 private final JdbcTemplate jdbc;public MemberOperatingScopePortImpl(JdbcTemplate jdbc){this.jdbc=jdbc;}
 @Override public Scope resolve(String tenant,String entity,String store){return read(tenant,entity,store,false);}
 private Scope read(String tenant,String entity,String store,boolean current){
  var rows=jdbc.queryForList("SELECT s.store_no,s.entity_no,s.name,b.brand_no,r.scope_revision FROM mch_store s JOIN mch_entity e ON e.tenant_no=s.tenant_no AND e.entity_no=s.entity_no AND e.deleted=0 AND e.status='ACTIVE' LEFT JOIN mch_brand_store_binding b ON b.tenant_no=s.tenant_no AND b.store_no=s.store_no AND b.status='ACTIVE' AND b.deleted=0 LEFT JOIN mch_brand r ON r.tenant_no=b.tenant_no AND r.brand_no=b.brand_no AND r.status='ACTIVE' AND r.deleted=0 WHERE s.tenant_no=? AND s.entity_no=? AND s.store_no=? AND s.status='ACTIVE' AND s.deleted=0"+(current?" FOR UPDATE":""),tenant,entity,store);
  if(rows.size()!=1)throw BizException.of(ErrorCode.NOT_FOUND);var row=rows.getFirst();Object brand=row.get("brand_no");if(brand!=null&&row.get("scope_revision")==null)throw BizException.of(ErrorCode.CONFLICT);
  return new Scope(tenant,entity,store,brand==null?"STORE":"BRAND",brand==null?store:brand.toString(),brand==null?0:((Number)row.get("scope_revision")).longValue(),Objects.toString(row.get("name"),""));
 }
 @Override public Scope lock(String tenant,String entity,String store){
  // A prior snapshot is used only to choose lock order. All accepted facts are current locking reads.
  var expected=resolve(tenant,entity,store);
  if("BRAND".equals(expected.scopeType()))jdbc.queryForList("SELECT brand_no FROM mch_brand WHERE tenant_no=? AND brand_no=? AND deleted=0 FOR UPDATE",tenant,expected.scopeNo());
  if(jdbc.queryForList("SELECT store_no FROM mch_store WHERE tenant_no=? AND entity_no=? AND store_no=? AND deleted=0 FOR UPDATE",tenant,entity,store).size()!=1)throw BizException.of(ErrorCode.NOT_FOUND);
  var actual=read(tenant,entity,store,true);if(!actual.scopeType().equals(expected.scopeType())||!actual.scopeNo().equals(expected.scopeNo()))throw BizException.of(ErrorCode.CONFLICT);return actual;
 }
 @Override public List<Scope> stores(Scope scope){
  var current=resolve(scope.tenantNo(),scope.entityNo(),scope.storeNo());if(!current.scopeType().equals(scope.scopeType())||!current.scopeNo().equals(scope.scopeNo())||current.revision()!=scope.revision())throw BizException.of(ErrorCode.CONFLICT);
  if("STORE".equals(scope.scopeType()))return List.of(current);
  return jdbc.queryForList("SELECT s.entity_no,s.store_no FROM mch_brand_store_binding b JOIN mch_store s ON s.tenant_no=b.tenant_no AND s.store_no=b.store_no AND s.deleted=0 AND s.status='ACTIVE' JOIN mch_entity e ON e.tenant_no=s.tenant_no AND e.entity_no=s.entity_no AND e.deleted=0 AND e.status='ACTIVE' WHERE b.tenant_no=? AND b.brand_no=? AND b.status='ACTIVE' AND b.deleted=0 ORDER BY s.store_no",scope.tenantNo(),scope.scopeNo()).stream().map(row->resolve(scope.tenantNo(),row.get("entity_no").toString(),row.get("store_no").toString())).toList();
 }
}
