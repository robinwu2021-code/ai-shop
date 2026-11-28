package ai.neargo.shop.merchant.port;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.spi.org.StoreChainPort;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@DisplayName("门店所属连锁：生效中的品牌绑定就是连锁标记，总部是品牌的签约主体")
class StoreChainPortImplTest {

    private JdbcTemplate db;
    private StoreChainPort port;

    @BeforeEach
    void setUp() {
        db = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:store-chain-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        db.execute("CREATE TABLE mch_store(tenant_no varchar(32),entity_no varchar(64),store_no varchar(64),"
                + "status varchar(24),deleted int)");
        db.execute("CREATE TABLE mch_brand(tenant_no varchar(32),brand_no varchar(64),management_no varchar(64),"
                + "legal_entity_no varchar(64),status varchar(24),scope_revision bigint,deleted int)");
        db.execute("CREATE TABLE mch_brand_store_binding(tenant_no varchar(32),binding_no varchar(64),"
                + "brand_no varchar(64),store_no varchar(64),status varchar(24),deleted int)");
        db.update("INSERT INTO mch_store VALUES('MAIN','HQ','S1','ACTIVE',0),('MAIN','F1','S2','ACTIVE',0),"
                + "('MAIN','HQ','S3','ACTIVE',0),('MAIN','HQ','S4','ACTIVE',0),('MAIN','X','S5','ACTIVE',0)");
        db.update("INSERT INTO mch_brand VALUES('MAIN','B1','G1','HQ','ACTIVE',7,0),"
                + "('MAIN','B2','G1','HQ','INACTIVE',1,0),('MAIN','B3','G2',NULL,'ACTIVE',2,0)");
        db.update("INSERT INTO mch_brand_store_binding VALUES('MAIN','BS1','B1','S1','ACTIVE',0),"
                + "('MAIN','BS2','B1','S2','ACTIVE',0),('MAIN','BS3','B1','S3','ENDED',0),"
                + "('MAIN','BS4','B2','S4','ACTIVE',0),('MAIN','BS5','B3','S5','ACTIVE',0)");
        port = new StoreChainPortImpl(db);
    }

    @Test
    void storeOfTheHeadquartersEntityIsDirect() {
        var chain = port.chainOf("MAIN", "S1").orElseThrow();
        assertThat(chain.brandNo()).isEqualTo("B1");
        assertThat(chain.managementNo()).isEqualTo("G1");
        assertThat(chain.headquartersEntityNo()).isEqualTo("HQ");
        assertThat(chain.storeEntityNo()).isEqualTo("HQ");
        assertThat(chain.revision()).isEqualTo(7);
        assertThat(chain.direct()).isTrue();
        assertThat(chain.franchised()).isFalse();
    }

    @Test
    void storeOfAnotherEntityIsFranchised() {
        var chain = port.chainOf("MAIN", "S2").orElseThrow();
        assertThat(chain.storeEntityNo()).isEqualTo("F1");
        assertThat(chain.franchised()).isTrue();
        assertThat(chain.direct()).isFalse();
    }

    @Test
    void endedBindingOrInactiveBrandIsNoChain() {
        assertThat(port.chainOf("MAIN", "S3")).as("绑定已结束").isEmpty();
        assertThat(port.chainOf("MAIN", "S4")).as("连锁已停用").isEmpty();
        assertThat(port.chainOf("MAIN", "S9")).as("没有这家店").isEmpty();
    }

    @Test
    void chainWithoutHeadquartersIsNeitherDirectNorFranchised() {
        var chain = port.chainOf("MAIN", "S5").orElseThrow();
        assertThat(chain.headquartersEntityNo()).isNull();
        assertThat(chain.direct()).isFalse();
        assertThat(chain.franchised()).isFalse();
    }
}
