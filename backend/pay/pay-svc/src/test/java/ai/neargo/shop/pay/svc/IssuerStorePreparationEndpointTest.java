package ai.neargo.shop.pay.svc;

import ai.neargo.shop.pay.client.PayStoredValueIssuerRequests;
import ai.neargo.shop.pay.storedvalue.issuer.*;
import ai.neargo.shop.spi.storedvalue.StoredValueIssuers.StoreAuthority;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;

class IssuerStorePreparationEndpointTest {
    @Test void internalTokenIsRequiredBeforeAnyIssuerWriteAndAuthorizedReplayIsStable() throws Exception {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:issuer-endpoint-"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");
        var j=new JdbcTemplate(ds);
        var sql=Files.readString(Path.of("../../shop-app/src/main/resources/db/migration/V546__stored_value_issuer_foundation.sql"))
                .replaceAll("(?m)^--.*$", "").replace(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_520_ci", "")
                .replace(" JSON", " VARCHAR(10000)").replace("CHECK (scope_type IN ('BRAND','STORE'))", "CHECK (CASE scope_type WHEN 'BRAND' THEN TRUE WHEN 'STORE' THEN TRUE ELSE FALSE END)");
        for(String part:sql.split(";"))if(!part.isBlank()) {
            var m=java.util.regex.Pattern.compile("CREATE TABLE ([a-z_]+)").matcher(part);
            if(m.find())part=part.replace("KEY uk_","KEY "+m.group(1)+"_uk_").replace("KEY ix_","KEY "+m.group(1)+"_ix_");j.execute(part);
        }
        var db=new IssuerRepository(new DataSourceTransactionManager(ds),new ObjectMapper());
        var endpoint=new InternalStoredValueIssuerEndpoint(new StoredValueIssuerService(db),new StoredValueStoreIssuerService(db),"issuer-prepare-test-token");
        var request=new PayStoredValueIssuerRequests.PrepareStore(new StoreAuthority("MAIN","OWNER","E1","S1","测试店",3));
        assertThat(endpoint.prepareStore(request,null).getStatusCode().value()).isEqualTo(401);
        assertThat(endpoint.prepareStore(request,"wrong").getStatusCode().value()).isEqualTo(401);
        assertThat(j.queryForObject("SELECT COUNT(*) FROM sv_issuer",Long.class)).isZero();
        var created=endpoint.prepareStore(request,"issuer-prepare-test-token");
        assertThat(created.getStatusCode().value()).isEqualTo(200);
        assertThat(endpoint.prepareStore(request,"issuer-prepare-test-token").getBody()).isEqualTo(created.getBody());
        assertThat(j.queryForObject("SELECT COUNT(*) FROM sv_issuer_version",Long.class)).isEqualTo(1);
    }
}
