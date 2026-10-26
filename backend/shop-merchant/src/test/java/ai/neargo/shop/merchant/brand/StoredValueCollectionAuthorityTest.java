package ai.neargo.shop.merchant.brand;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.merchant.brand.ManagementModels.*;
import ai.neargo.shop.spi.storedvalue.StoredValueCollections.*;
import ai.neargo.shop.spi.storedvalue.StoredValueWallets.ExecutionScope;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class StoredValueCollectionAuthorityTest {
 @Test void spaceOwnershipDoesNotGrantCollectionButExplicitEntityPermissionDoes()throws Exception{
  var f=new BrandFixture();String m=f.no(f.space(),"managementNo");var authority=new ai.neargo.shop.merchant.brand.impl.StoredValueFinancialAuthorityServiceImpl(f.repo,f.scopes);var s=new ExecutionScope("MAIN","MA1",m,"E2","S2",0,"B1","BIND",1,"CNY",1,2);var r=new Requirement(s,"I1","IV1","E1",true);assertNull(authority.collectionProof(f.owner,s,r));
  f.scopes.grant(f.owner,m,new GrantCommand(f.owner.actorKey(),"ENTITY","E1","sv.issuer.collection.confirm",null,null,false,null,false));var proof=authority.collectionProof(f.owner,s,r);assertNotNull(proof);assertEquals("MCH:MA1",proof.authenticatedActorKey());assertEquals(r,proof.requirement());assertTrue(proof.expiresAt().isAfter(java.time.Instant.now()));
 }
 /** 门店口径的财务查询只认「我持有财务权的主体名下的店」—— 别家的店此前照样放行 */
 @Test void financeLookupRejectsStoresOutsideTheActorsFinanceEntities()throws Exception{
  var f=new BrandFixture();String m=f.no(f.space(),"managementNo");var authority=new ai.neargo.shop.merchant.brand.impl.StoredValueFinancialAuthorityServiceImpl(f.repo,f.scopes);
  authority.prepare(f.owner,m,"E1");
  assertEquals("E1",authority.financialLookup(f.owner,m,new ai.neargo.shop.spi.storedvalue.StoredValueIssuers.ScopeRef("STORE","S1")).operatingEntityNo());
  var foreign=assertThrows(BizException.class,()->authority.financialLookup(f.owner,m,new ai.neargo.shop.spi.storedvalue.StoredValueIssuers.ScopeRef("STORE","S2")));
  assertEquals(ai.neargo.shop.common.ErrorCode.FORBIDDEN,foreign.errorCode());
  assertThrows(BizException.class,()->authority.financial(f.owner,m,java.util.List.of(new ai.neargo.shop.spi.storedvalue.StoredValueSettlements.IssuerGrant("I2","STORE","S2")),java.util.Set.of()));
 }
 @Test void anotherTenantOrActorCannotReuseTheCollectionRequirement()throws Exception{
  var f=new BrandFixture();String m=f.no(f.space(),"managementNo");var authority=new ai.neargo.shop.merchant.brand.impl.StoredValueFinancialAuthorityServiceImpl(f.repo,f.scopes);var s=new ExecutionScope("MAIN","MA1",m,"E2","S2",0,"B1","BIND",1,"CNY",1,2);var r=new Requirement(s,"I1","IV1","E1",true);assertThrows(BizException.class,()->authority.collectionProof(f.other,s,r));assertThrows(BizException.class,()->authority.collectionProof(new Actor("OTHER","MCH:MA1"),s,r));
 }
}
