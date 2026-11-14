package ai.neargo.shop.pay.storedvalue.issuer;

import ai.neargo.shop.pay.storedvalue.issuer.impl.StoredValueIssuerQueryServiceImpl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.pay.storedvalue.service.StoredValueCursorCodec;
import ai.neargo.shop.spi.storedvalue.StoredValueWallets.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;

class DualIssuerAccountIsolationTest {
    static class Fixture {
        final DriverManagerDataSource ds=new DriverManagerDataSource("jdbc:h2:mem:p5-wallet-"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");
        final JdbcTemplate jdbc=new JdbcTemplate(ds);final ObjectMapper json=new ObjectMapper();final IssuerRepository db=new IssuerRepository(new DataSourceTransactionManager(ds),json);
        final StoredValueIssuerQueryService query=new StoredValueIssuerQueryServiceImpl(db,new StoredValueCursorCodec("p5-test-reference-key-longer-than-32-characters",json),true,true,false);
        Fixture(){
            jdbc.execute("CREATE TABLE sv_issuer(tenant_no VARCHAR(32),issuer_no VARCHAR(64),scope_type VARCHAR(24),scope_no VARCHAR(64),name VARCHAR(64),status VARCHAR(24),write_mode VARCHAR(32),current_issuer_version_no VARCHAR(64),deleted INT DEFAULT 0)");
            jdbc.execute("CREATE TABLE sv_issuer_version(tenant_no VARCHAR(32),issuer_no VARCHAR(64),issuer_version_no VARCHAR(64),settlement_entity_no VARCHAR(64),currency VARCHAR(8),deleted INT DEFAULT 0)");
            jdbc.execute("CREATE TABLE sv_issuer_store_access(tenant_no VARCHAR(32),issuer_no VARCHAR(64),store_no VARCHAR(64),operating_entity_no VARCHAR(64),source_binding_no VARCHAR(64),source_revision BIGINT,can_issue INT,can_redeem INT,status VARCHAR(24),effective_from TIMESTAMP,effective_to TIMESTAMP,deleted INT DEFAULT 0)");
            jdbc.execute("CREATE TABLE sv_account(tenant_no VARCHAR(32),account_no VARCHAR(64),issuer_no VARCHAR(64),issuer_version_no VARCHAR(64),entity_no VARCHAR(64),store_no VARCHAR(64),customer_no VARCHAR(64),user_no VARCHAR(64),currency VARCHAR(8),status VARCHAR(24),principal_minor BIGINT,bonus_minor BIGINT,held_principal_minor BIGINT,held_bonus_minor BIGINT,version BIGINT,deleted INT DEFAULT 0)");
            issuer("IA","STORE","A");issuer("IB","STORE","B");issuer("IC","BRAND","CHAIN");
            access("IA","A","A");access("IB","B","B");access("IC","A","BA");access("IC","B","BB");
            wallet("WA","IA","A",6000);wallet("WB","IB","B",2000);wallet("WC","IC",null,13000);
        }
        void issuer(String no,String type,String scope){jdbc.update("INSERT INTO sv_issuer VALUES('MAIN',?,?,?,?, 'ACTIVE','ISSUER',?,0)",no,type,scope,no,"V"+no);jdbc.update("INSERT INTO sv_issuer_version VALUES('MAIN',?,?,'E1','CNY',0)",no,"V"+no);}
        void access(String issuer,String store,String binding){jdbc.update("INSERT INTO sv_issuer_store_access VALUES('MAIN',?,?,'E1',?,0,1,1,'ACTIVE',DATEADD('DAY',-1,CURRENT_TIMESTAMP),NULL,0)",issuer,store,binding);}
        void wallet(String no,String issuer,String store,long amount){jdbc.update("INSERT INTO sv_account VALUES('MAIN',?,?,?,'E1',?,'PERSON',NULL,'CNY','ACTIVE',?,0,0,0,0,0)",no,issuer,"V"+issuer,store,amount);}
        Query scope(String store){return new Query(new ExecutionScope("MAIN","CLERK","M1","E1",store,0,"CHAIN","B"+store,0,"CNY"),new Customer("PERSON",null));}
    }
    @Test void switchStorePreservesBrandWalletAndInvalidatesOldSelection(){
        var f=new Fixture();var a=f.query.accounts(f.scope("A"));var b=f.query.accounts(f.scope("B"));
        assertThat(a).extracting(v->v.account().balanceMinor()).containsExactly(6000L,13000L);assertThat(b).extracting(v->v.account().balanceMinor()).containsExactly(2000L,13000L);
        assertThatThrownBy(()->f.query.select(f.scope("B"),a.get(1).issuerRef(),a.get(1).accountRef())).isInstanceOf(BizException.class);
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM sv_account",Long.class)).isEqualTo(3);
    }
    @Test void cannotCombineOrReplaceOneIssuerWithAnotherAccount(){
        var f=new Fixture();var choices=f.query.accounts(f.scope("A"));var brand=choices.get(1);var selected=f.query.select(f.scope("A"),brand.issuerRef(),brand.accountRef());assertThat(selected.accountNo()).isEqualTo("WC");assertThat(selected.issuerNo()).isEqualTo("IC");
        assertThatThrownBy(()->f.query.select(f.scope("A"),choices.getFirst().issuerRef(),brand.accountRef())).isInstanceOf(BizException.class);
        var other=new Query(f.scope("A").scope(),new Customer("ANOTHER_PERSON",null));assertThatThrownBy(()->f.query.select(other,brand.issuerRef(),brand.accountRef())).isInstanceOf(BizException.class);
    }
    @Test void missingWalletStaysNullAndStoppedIssuanceStillAllowsRedemption(){
        var f=new Fixture();f.jdbc.update("DELETE FROM sv_account WHERE account_no='WA'");f.jdbc.update("UPDATE sv_issuer SET status='ISSUE_STOPPED' WHERE issuer_no='IC'");
        var choices=f.query.accounts(f.scope("A"));assertThat(choices.getFirst().account()).isNull();assertThat(choices.getFirst().accountRef()).isNull();assertThat(choices.get(1).canRecharge()).isFalse();assertThat(choices.get(1).canRedeem()).isTrue();assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM sv_account",Long.class)).isEqualTo(2);
    }
    @Test void changedBindingAndCrossEntityCannotExecuteBrandMoney(){
        var f=new Fixture();f.jdbc.update("UPDATE sv_issuer_store_access SET source_revision=1 WHERE issuer_no='IC' AND store_no='A'");var choices=f.query.accounts(f.scope("A"));assertThat(choices.get(1).canRedeem()).isFalse();assertThat(choices.get(1).unavailableReason()).isEqualTo("STORE_SCOPE_CHANGED");
        f.jdbc.update("UPDATE sv_issuer_version SET settlement_entity_no='E2' WHERE issuer_no='IC'");f.jdbc.update("UPDATE sv_account SET entity_no='E2' WHERE issuer_no='IC'");choices=f.query.accounts(f.scope("B"));assertThat(choices.get(1).canRedeem()).isFalse();assertThat(choices.get(1).unavailableReason()).isEqualTo("CROSS_ENTITY_SETTLEMENT_UNAVAILABLE");
    }
}
