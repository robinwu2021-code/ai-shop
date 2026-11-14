package ai.neargo.shop.pay.storedvalue.issuer;
import ai.neargo.shop.pay.storedvalue.issuer.impl.StoredValueStoreIssuerServiceImpl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.spi.storedvalue.StoredValueIssuers.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
class IssuerConfigurationStageTest {
 @Test void currentLiabilityBlocksFinancialRuleChangesButNameAndRoutesKeepImmutableHistory()throws Exception{
  var helper=new IssuerCollectionRouteTest();var f=helper.fixture();var scope=helper.scope("STORE");
  var original=f.service().configure(scope,helper.command("E1"),"FIRST");
  f.jdbc().update("INSERT INTO sv_account(tenant_no,issuer_no,principal_minor,bonus_minor,held_principal_minor,held_bonus_minor,deleted) VALUES('MAIN',?,100,30,0,0,0)",original.issuerNo());
  var changed=new Config("Changed","E1","CENTRALIZED","PRINCIPAL_COMPONENT","PRO_RATA","CNY","Asia/Shanghai",original.routes(),0);
  assertThat(f.service().check(scope,original.issuerNo(),changed).blockers()).contains("LIABILITY_RULES_LOCKED");
  assertThatThrownBy(()->f.service().configure(scope,changed,"BLOCK")).isInstanceOf(BizException.class);
  assertThat(f.jdbc().queryForObject("SELECT COUNT(*) FROM sv_issuer_version",Long.class)).isEqualTo(1);
  var renamed=new Config("Renamed","E1","CENTRALIZED","INTERNAL_RECON","PRO_RATA","CNY","Asia/Shanghai",original.routes(),0);
  assertThat(f.service().check(scope,original.issuerNo(),renamed).allowed()).isTrue();
  var next=f.service().configure(scope,renamed,"NEXT");
  assertThat(next.issuerVersionNo()).isNotEqualTo(original.issuerVersionNo());
  assertThat(f.service().configure(scope,renamed,"NEXT")).isEqualTo(next);
  assertThat(f.jdbc().queryForObject("SELECT principal_minor+bonus_minor FROM sv_account",Long.class)).isEqualTo(130);
 }
 @Test void preparedStoreNeedsExplicitCutoverAndFenceDisablesEveryCapability()throws Exception{
  var helper=new IssuerCollectionRouteTest();var f=helper.fixture();
  var db=new IssuerRepository(new DataSourceTransactionManager(f.jdbc().getDataSource()),new ObjectMapper());
  var stores=new StoredValueStoreIssuerServiceImpl(db);
  var prepared=stores.prepare(new StoreAuthority("MAIN","OWNER","E1","S1","Store",4));
  org.springframework.test.util.ReflectionTestUtils.setField(f.service(),"issuerMode",true);
  var query=new StoreQuery("MAIN","OPERATOR","E1","S1");
  assertThat(f.service().storeOptions(query).issuers().getFirst().canRecharge()).isFalse();
  f.jdbc().update("UPDATE sv_issuer SET status='ACTIVE',write_mode='ISSUER'");
  f.jdbc().update("UPDATE sv_issuer_store_access SET status='ACTIVE',can_issue=1,can_redeem=1");
  assertThat(f.service().storeOptions(query).issuers().getFirst().canRecharge()).isTrue();
  f.jdbc().update("UPDATE sv_issuer SET write_mode='FENCED'");
  var fenced=f.service().storeOptions(query).issuers().getFirst();
  assertThat(fenced.canRecharge()).isFalse();assertThat(fenced.canRedeem()).isFalse();
  assertThat(fenced.unavailableReason()).isEqualTo("MAINTENANCE_IN_PROGRESS");
  assertThatThrownBy(()->f.service().storeOptions(new StoreQuery("MAIN","OPERATOR","E2","S1"))).isInstanceOf(BizException.class);
  assertThat(f.service().storeOptions(new StoreQuery("MAIN","OPERATOR","E1","OTHER")).issuers()).isEmpty();
 }
 @Test void accessRefreshIsMonotonicDoesNotActivateAndCannotRunDuringMaintenance()throws Exception{
  var helper=new IssuerCollectionRouteTest();var f=helper.fixture();
  var db=new IssuerRepository(new DataSourceTransactionManager(f.jdbc().getDataSource()),new ObjectMapper());var stores=new StoredValueStoreIssuerServiceImpl(db);
  stores.prepare(new StoreAuthority("MAIN","OWNER","E1","S1","Store",4));
  assertThat(stores.refresh(new StoreAuthority("MAIN","OWNER","E1","S1","Store",5)).status()).isEqualTo("DRAFT");
  assertThat(f.jdbc().queryForObject("SELECT source_revision FROM sv_issuer_store_access",Long.class)).isEqualTo(5);
  assertThat(f.jdbc().queryForObject("SELECT can_issue+can_redeem FROM sv_issuer_store_access",Long.class)).isZero();
  assertThatThrownBy(()->stores.refresh(new StoreAuthority("MAIN","OWNER","E1","S1","Store",4))).isInstanceOf(BizException.class);
  f.jdbc().update("UPDATE sv_issuer SET write_mode='FENCED'");
  assertThatThrownBy(()->stores.refresh(new StoreAuthority("MAIN","OWNER","E1","S1","Store",6))).isInstanceOf(BizException.class);
 }
}
