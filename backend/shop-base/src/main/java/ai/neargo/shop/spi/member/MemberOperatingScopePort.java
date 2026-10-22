package ai.neargo.shop.spi.member;
import java.util.List;
/** Merchant-owned organization facts. Callers authorize the executing store before invoking. */
public interface MemberOperatingScopePort {
 record Scope(String tenantNo,String entityNo,String storeNo,String scopeType,String scopeNo,long revision,String storeName){}
 Scope resolve(String tenant,String entity,String store);
 /** Acquire the store row in the caller's transaction, then resolve current organization facts. */
 default Scope lock(String tenant,String entity,String store){return resolve(tenant,entity,store);}
 List<Scope> stores(Scope scope);
}
