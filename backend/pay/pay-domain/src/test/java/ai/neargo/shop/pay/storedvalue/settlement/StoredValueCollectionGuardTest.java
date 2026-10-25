package ai.neargo.shop.pay.storedvalue.settlement;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.pay.storedvalue.issuer.StoredValueCollectionGuard;
import ai.neargo.shop.spi.storedvalue.StoredValueCollections.*;
import ai.neargo.shop.spi.storedvalue.StoredValueCollections.Proof;
import ai.neargo.shop.spi.storedvalue.StoredValueWallets.*;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class StoredValueCollectionGuardTest {
 ExecutionScope scope(String entity,String binding,String actor){return new ExecutionScope("MAIN",actor,"M1",entity,"S1",1,"B1",binding,2,"CNY",3,2);}
 @Test void foreignCentralCollectionRequiresExactLiveCoreProof()throws Exception{
  var f=new StoredValueSettlementFixture();var guard=new StoredValueCollectionGuard(f.db);var s=scope("E2","BIND1","OWNER");var requirement=guard.requirement(s);assertTrue(requirement.required());var q=new Query(s,new Customer("PERSON",null));assertThrows(BizException.class,()->guard.require(q,"I1","IV1","E1"));
  var proof=new Proof(requirement,"MCH:OWNER",4,Instant.now().plusSeconds(60));var allowed=new Query(s,q.customer(),proof);assertDoesNotThrow(()->guard.require(allowed,"I1","IV1","E1"));assertThrows(BizException.class,()->guard.require(allowed,"I1","IV2","E1"));assertThrows(BizException.class,()->guard.require(new Query(scope("E2","CHANGED","OWNER"),q.customer(),proof),"I1","IV1","E1"));
 }
 @Test void expiredOrOtherActorProofCannotCollectAndLocalScopeKeepsLegacyBehavior()throws Exception{
  var f=new StoredValueSettlementFixture();var guard=new StoredValueCollectionGuard(f.db);var s=scope("E2","BIND1","OWNER");var r=guard.requirement(s);assertFalse(guard.canCollect(new Query(s,new Customer("PERSON",null),new Proof(r,"MCH:OTHER",4,Instant.now().plusSeconds(60))),"I1","IV1","E1"));assertFalse(guard.canCollect(new Query(s,new Customer("PERSON",null),new Proof(r,"MCH:OWNER",4,Instant.now().minusSeconds(1))),"I1","IV1","E1"));assertTrue(guard.canCollect(new Query(scope("E1","BIND1","OWNER"),new Customer("PERSON",null)),"I1","IV1","E1"));
 }
}
