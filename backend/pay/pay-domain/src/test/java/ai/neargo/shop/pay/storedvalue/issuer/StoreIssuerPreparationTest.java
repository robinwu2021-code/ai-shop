package ai.neargo.shop.pay.storedvalue.issuer;
import ai.neargo.shop.pay.storedvalue.issuer.impl.StoredValueStoreIssuerServiceImpl;


import ai.neargo.shop.common.BizException;
import ai.neargo.shop.spi.storedvalue.StoredValueIssuers.StoreAuthority;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;

class StoreIssuerPreparationTest {
    record Fixture(JdbcTemplate jdbc,StoredValueStoreIssuerService service) { }
    Fixture fixture() throws Exception {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:store-issuer-"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");
        var j=new JdbcTemplate(ds);
        var ddl=Files.readString(Path.of("../../shop-app/src/main/resources/db/migration/V548__stored_value_issuer_foundation.sql"))
            .replaceAll("(?m)^--.*$", "").replace(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_520_ci", "")
            .replace(" JSON", " VARCHAR(10000)").replace("CHECK (scope_type IN ('BRAND','STORE'))", "CHECK (CASE scope_type WHEN 'BRAND' THEN TRUE WHEN 'STORE' THEN TRUE ELSE FALSE END)");
        for(String part:ddl.split(";"))if(!part.isBlank()) {
            var m=java.util.regex.Pattern.compile("CREATE TABLE ([a-z_]+)").matcher(part);
            if(m.find())part=part.replace("KEY uk_","KEY "+m.group(1)+"_uk_").replace("KEY ix_","KEY "+m.group(1)+"_ix_");
            j.execute(part);
        }
        j.execute("ALTER TABLE sv_issuer ADD COLUMN write_mode varchar DEFAULT 'LEGACY' NOT NULL");
        j.execute("CREATE TABLE sv_account(account_no varchar,principal_minor bigint,bonus_minor bigint)");
        j.update("INSERT INTO sv_account VALUES('ORIGINAL',5000,1000)");
        return new Fixture(j,new StoredValueStoreIssuerServiceImpl(new IssuerRepository(new DataSourceTransactionManager(ds),new ObjectMapper())));
    }
    StoreAuthority scope(String store,String entity){return new StoreAuthority("MAIN","OWNER",entity,store,"本店",4);}
    @Test void preparationIsIdempotentAndDoesNotActivateOrRewriteOldMoney() throws Exception {
        var f=fixture();var first=f.service().prepare(scope("S1","E1"));
        assertThat(f.service().prepare(scope("S1","E1"))).isEqualTo(first);
        assertThat(first.scopeType()).isEqualTo("STORE");assertThat(first.scopeNo()).isEqualTo("S1");
        assertThat(first.status()).isEqualTo("DRAFT");assertThat(first.settlementEntityNo()).isEqualTo("E1");
        assertThat(first.routes().getFirst().paymentMerchantNo()).isNull();
        assertThat(f.jdbc().queryForObject("SELECT COUNT(*) FROM sv_issuer_version",Long.class)).isEqualTo(1);
        assertThat(f.jdbc().queryForObject("SELECT write_mode FROM sv_issuer",String.class)).isEqualTo("LEGACY");
        assertThat(f.jdbc().queryForObject("SELECT account_no FROM sv_account",String.class)).isEqualTo("ORIGINAL");
        assertThat(f.jdbc().queryForObject("SELECT principal_minor+bonus_minor FROM sv_account",Long.class)).isEqualTo(6000);
        assertThat(f.jdbc().queryForObject("SELECT status FROM sv_issuer_store_access",String.class)).isEqualTo("PREPARING");
        assertThat(f.jdbc().queryForObject("SELECT can_issue+can_redeem FROM sv_issuer_store_access",Integer.class)).isZero();
        assertThat(f.jdbc().queryForObject("SELECT entity_acceptance FROM sv_issuer_version",String.class)).contains("LEGACY_STORE_SCOPE").doesNotContain("managementNo");
    }
    @Test void sameEntityStoresRemainIndependentAndEntityChangeCannotReinterpretIssuer() throws Exception {
        var f=fixture();var a=f.service().prepare(scope("S1","E1"));var b=f.service().prepare(scope("S2","E1"));
        assertThat(a.issuerNo()).isNotEqualTo(b.issuerNo());
        assertThatThrownBy(()->f.service().prepare(scope("S1","E2"))).isInstanceOf(BizException.class);
        assertThat(f.jdbc().queryForObject("SELECT COUNT(*) FROM sv_issuer",Long.class)).isEqualTo(2);
        assertThat(f.jdbc().queryForObject("SELECT COUNT(*) FROM sv_issuer_version",Long.class)).isEqualTo(2);
    }
}
