package ai.neargo.shop.pay.storedvalue.migration;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import tools.jackson.databind.ObjectMapper;
import ai.neargo.shop.pay.storedvalue.issuer.IssuerRepository;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.startsWith;

class StoredValueAccountKeyInspectorTest {
    record Fixture(JdbcTemplate jdbc,IssuerRepository db,StoredValueAccountKeyInspector inspector){}
    Fixture fixture(){
        var ds=new DriverManagerDataSource("jdbc:h2:mem:key-"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");
        var j=new JdbcTemplate(ds);
        j.execute("CREATE TABLE sv_account(tenant_no varchar,account_no varchar,issuer_no varchar,issuer_version_no varchar,customer_no varchar,currency varchar,store_no varchar,entity_no varchar,deleted int)");
        j.execute("CREATE TABLE sv_issuer(tenant_no varchar,issuer_no varchar,scope_type varchar,scope_no varchar,write_mode varchar,deleted int)");
        j.execute("CREATE TABLE sv_issuer_version(tenant_no varchar,issuer_no varchar,issuer_version_no varchar,currency varchar,deleted int)");
        j.update("INSERT INTO sv_issuer VALUES('MAIN','I1','STORE','S1','FENCED',0)");
        j.update("INSERT INTO sv_issuer_version VALUES('MAIN','I1','IV1','CNY',0)");
        j.update("INSERT INTO sv_account VALUES('MAIN','A1','I1','IV1','P1','CNY','S1','E1',0)");
        var db=spy(new IssuerRepository(new DataSourceTransactionManager(ds),new ObjectMapper()));
        var rows=new ArrayList<Map<String,Object>>();
        Map<String,List<String>> keys=new LinkedHashMap<>();
        keys.put("PRIMARY",List.of("id"));keys.put("uk_sv_account",List.of("tenant_no","account_no"));
        keys.put("uk_sv_owner",List.of("tenant_no","user_no","entity_no","store_no","currency"));
        keys.put("uk_sv_customer_owner",List.of("tenant_no","customer_no","entity_no","store_no","currency"));
        keys.forEach((name,columns)->{for(int n=0;n<columns.size();n++)rows.add(Map.of("index_name",name,"non_unique",0,"seq_in_index",n+1,"column_name",columns.get(n),"index_type","BTREE"));});
        // Only MySQL's metadata table is substituted. All account/issuer scans run real SQL on isolated rows.
        doReturn(rows).when(db).rows(startsWith("SELECT INDEX_NAME"));
        return new Fixture(j,db,new StoredValueAccountKeyInspector(db));
    }
    @Test void inspectsAllTenantsIncludingDeletedRowsWithoutChangingThem(){
        var f=fixture();assertThat(f.inspector().inspect().nextStep()).isEqualTo("ADD_ISSUER_KEY");
        f.jdbc().update("INSERT INTO sv_account VALUES('OTHER','A2',NULL,NULL,'P2','CNY','S2','E2',1)");
        var plan=f.inspector().inspect();
        assertThat(plan.ready()).isFalse();assertThat(plan.accounts()).isEqualTo(2);
        assertThat(plan.issues()).contains("ACCOUNT_DIMENSIONS_INCOMPLETE","ACCOUNT_ISSUER_REFERENCE_INVALID");
        assertThat(f.jdbc().queryForObject("SELECT COUNT(*) FROM sv_account",Long.class)).isEqualTo(2);
        assertThat(f.jdbc().queryForObject("SELECT write_mode FROM sv_issuer",String.class)).isEqualTo("FENCED");
    }
    @Test void databaseGroupingAndGlobalFenceBlockDuplicateOrUnfencedEmptyIssuer(){
        var f=fixture();
        f.jdbc().update("INSERT INTO sv_account SELECT tenant_no,'A2',issuer_no,issuer_version_no,customer_no,currency,store_no,entity_no,1 FROM sv_account");
        assertThat(f.inspector().inspect().issues()).contains("ISSUER_CUSTOMER_DUPLICATE");
        f.jdbc().update("DELETE FROM sv_account WHERE account_no='A2'");
        f.jdbc().update("INSERT INTO sv_issuer VALUES('OTHER','I2','STORE','S2','LEGACY',0)");
        assertThat(f.inspector().inspect().issues()).contains("LEDGER_WRITERS_NOT_FENCED");
    }
    @Test void wrongFrozenVersionCurrencyOrStoreBlocksThePlan(){
        var f=fixture();
        f.jdbc().update("UPDATE sv_issuer_version SET issuer_no='I2'");
        assertThat(f.inspector().inspect().issues()).contains("ACCOUNT_ISSUER_REFERENCE_INVALID");
        f.jdbc().update("UPDATE sv_issuer_version SET issuer_no='I1',currency='USD'");
        assertThat(f.inspector().inspect().ready()).isFalse();
        f.jdbc().update("UPDATE sv_issuer_version SET currency='CNY'");
        f.jdbc().update("UPDATE sv_account SET store_no='S2'");
        assertThat(f.inspector().inspect().ready()).isFalse();
    }

    @Test void prefixIndexMetadataCannotMasqueradeAsTheExpectedUniqueKey(){
        var f=fixture();
        var rows=new ArrayList<Map<String,Object>>();
        for(var row:f.db().rows("SELECT INDEX_NAME")) {
            var changed=new HashMap<>(row);
            if("uk_sv_owner".equals(row.get("index_name"))&&"user_no".equals(row.get("column_name")))changed.put("sub_part",8);
            rows.add(changed);
        }
        doReturn(rows).when(f.db()).rows(startsWith("SELECT INDEX_NAME"));
        var plan=f.inspector().inspect();
        assertThat(plan.ready()).isFalse();assertThat(plan.statements()).isEmpty();
        assertThat(plan.issues()).contains("INDEX_DEFINITION_MISMATCH:uk_sv_owner");
    }
    @Test void unavailableMetadataFailsInsteadOfProducingAnEmptyGreenPlan(){
        var f=fixture();
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("unavailable")).when(f.db()).rows(startsWith("SELECT INDEX_NAME"));
        assertThatThrownBy(()->f.inspector().inspect()).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
}
