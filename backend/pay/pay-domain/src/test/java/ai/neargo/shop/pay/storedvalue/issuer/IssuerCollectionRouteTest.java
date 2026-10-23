package ai.neargo.shop.pay.storedvalue.issuer;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.spi.storedvalue.StoredValueIssuers.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;

class IssuerCollectionRouteTest {
    private final Route wx = new Route("PM_WX", "E1", "S1", "WECHAT", "JSAPI");
    private final Route ali = new Route("PM_ALI", "E1", "S1", "ALIPAY", "APP");
    private AuthorizedScope scope(String type) {
        return new AuthorizedScope("MAIN", "U1", "MGS1", type, type.equals("STORE") ? "S1" : "B1",
                1, "E1", Set.of("E1"), List.of(wx, ali));
    }
    private Config command(String entity) {
        return new Config("测试发行", entity, "CENTRALIZED", "INTERNAL_RECON", "PRO_RATA", "CNY",
                "Asia/Shanghai", List.of(wx, ali), 0);
    }
    private StoredValueIssuerService service() throws Exception {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(ds);
        var ddl = Files.readString(Path.of("../../shop-app/src/main/resources/db/migration/V546__stored_value_issuer_foundation.sql"));
        ddl = ddl.replace(" JSON", " VARCHAR(10000)").replace("CHECK (scope_type IN ('BRAND','STORE'))", "CHECK (CASE scope_type WHEN 'BRAND' THEN TRUE WHEN 'STORE' THEN TRUE ELSE FALSE END)");
        ddl = ddl.replaceAll("(?m)^--.*$", "").replace(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_520_ci", "");
        for (String part : ddl.split(";")) if (!part.isBlank()) {
            var table=java.util.regex.Pattern.compile("CREATE TABLE ([a-z_]+)").matcher(part);
            if(table.find()) part=part.replace("KEY uk_", "KEY "+table.group(1)+"_uk_");
            jdbc.execute(part);
        }
        return new StoredValueIssuerService(new IssuerRepository(new DataSourceTransactionManager(ds), new ObjectMapper()));
    }
    @Test void channelDoesNotSplitIssuerAndConfigurationReplayDoesNotCreateAnotherVersion() throws Exception {
        var service=service();var scope=scope("STORE");
        var issuer=service.configure(scope,command("E1"),"C1");
        assertThat(service.configure(scope,command("E1"),"C1")).isEqualTo(issuer);
        assertThat(service.collectionRoute(scope,issuer.issuerNo(),"WECHAT","JSAPI","S1").issuerNo()).isEqualTo(issuer.issuerNo());
        assertThat(service.collectionRoute(scope,issuer.issuerNo(),"ALIPAY","APP","S1").issuerNo()).isEqualTo(issuer.issuerNo());
        assertThat(issuer.status()).isEqualTo("DRAFT");
    }
    @Test void unauthorizedSettlementOrMerchantIsRejectedBeforePersisting() throws Exception {
        var service=service();
        assertThatThrownBy(()->service.configure(scope("STORE"),command("E2"),"C1")).isInstanceOf(BizException.class);
        var bad=new Config("发行", "E1", "CENTRALIZED", "INTERNAL_RECON", "PRO_RATA", "CNY", "Asia/Shanghai",
                List.of(new Route("OTHER", "E1", "S1", "WECHAT", "JSAPI")),0);
        assertThatThrownBy(()->service.configure(scope("STORE"),bad,"C2")).isInstanceOf(BizException.class);
        assertThat(service.find(scope("STORE"))).isEmpty();
    }
    @Test void brandConfigurationIsDraftAndCannotBeUsedAsAnActiveCollectionRoute() throws Exception {
        var service=service();var scope=scope("BRAND");var issuer=service.configure(scope,command("E1"),"B1");
        assertThat(issuer.status()).isEqualTo("DRAFT");
        assertThatThrownBy(()->service.requireIssuable(scope,issuer.issuerNo())).isInstanceOf(BizException.class);
        assertThatThrownBy(()->service.collectionRoute(scope("STORE"),issuer.issuerNo(),"WECHAT","JSAPI","S1")).isInstanceOf(BizException.class);
    }
    @Test void replayReturnsItsOriginalConfigurationAfterAnotherVersionIsCreated() throws Exception {
        var service=service();var scope=scope("STORE");var first=service.configure(scope,command("E1"),"C1");
        var changed=new Config("新的发行名称","E1","CENTRALIZED","INTERNAL_RECON","PRO_RATA","CNY","Asia/Shanghai",List.of(wx,ali),0);
        var second=service.configure(scope,changed,"C2");
        assertThat(second.issuerNo()).isEqualTo(first.issuerNo());
        assertThat(second.issuerVersionNo()).isNotEqualTo(first.issuerVersionNo());
        assertThat(service.configure(scope,command("E1"),"C1")).isEqualTo(first);
        assertThat(service.find(scope).orElseThrow()).isEqualTo(second);
        assertThatThrownBy(()->service.configure(scope,changed,"C1")).isInstanceOf(BizException.class);
    }
}
