package ai.neargo.shop.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.common.data.scope.DataScopeSpec;
import ai.neargo.shop.auth.ScopeDim;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.config.PrimaryPersistenceConfiguration;
import ai.neargo.shop.product.mapper.ProductMappers.GoodsMapper;
import ai.neargo.shop.product.mapper.ProductMappers.SkuMapper;
import ai.neargo.shop.product.port.GoodsCatalogQueryPortImpl;
import ai.neargo.shop.spi.product.GoodsCatalogQueryPort;
import java.util.Set;
import java.util.List;
import java.util.stream.IntStream;
import java.util.UUID;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

class GoodsCatalogQueryPortTest {
    private void run(java.util.function.BiConsumer<GoodsCatalogQueryPort,JdbcTemplate> action) {
        new ApplicationContextRunner().withUserConfiguration(Fixture.class)
                .withPropertyValues("spring.datasource.url=jdbc:h2:mem:catalog_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1",
                        "spring.datasource.username=sa","spring.datasource.password=")
                .run(c -> {
                    assertThat(c).hasNotFailed();var sql=new JdbcTemplate(c.getBean(DataSource.class));
                    sql.execute("CREATE TABLE prd_goods(id BIGINT PRIMARY KEY,goods_no VARCHAR(32),entity_no VARCHAR(32),store_no VARCHAR(32),"
                            +"title VARCHAR(100),cover VARCHAR(200),on_sale BOOLEAN,audit_status VARCHAR(24),version BIGINT,deleted INT DEFAULT 0)");
                    sql.execute("CREATE TABLE prd_sku(id BIGINT PRIMARY KEY,sku_no VARCHAR(32),goods_no VARCHAR(32),entity_no VARCHAR(32),"
                            +"spec VARCHAR(100),market VARCHAR(16),version BIGINT,deleted INT DEFAULT 0)");
                    sql.update("INSERT INTO prd_goods VALUES(1,'G','E','S','菜品','cover',TRUE,'APPROVED',3,0)");
                    sql.update("INSERT INTO prd_sku VALUES(1,'SKU2','G','E','大份','CN',2,0),(2,'SKU1','G','E','小份','CN',1,0),"
                            +"(3,'ARCHIVED','G','E','归档','CN',0,1),(4,'FOREIGN','G','OTHER','坏归属','CN',0,0),"
                            +"(5,'OTHER_MARKET','G','E','非当前市场','AE',0,0)");
                    action.accept(c.getBean(GoodsCatalogQueryPort.class),sql);
                });
    }
    @Test void returnsAllSkuFactsWithoutMoneyOrStock() {
        run((s,sql) -> {
            var r=s.getGoods("E","S","G");
            assertThat(r.goodsNo()).isEqualTo("G");assertThat(r.entityNo()).isEqualTo("E");
            assertThat(r.storeNo()).isEqualTo("S");assertThat(r.title()).isEqualTo("菜品");
            assertThat(r.version()).isEqualTo(3);
            assertThat(r.skus()).extracting(GoodsCatalogQueryPort.SkuFact::skuNo).containsExactly("SKU1","SKU2");
            assertThat(new ObjectMapper().writeValueAsString(r)).doesNotContain("price","stock","available");
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM prd_goods",Integer.class)).isEqualTo(1);
        });
    }
    @Test void deniesForeignOrArchivedGoodsAndSku() {
        run((s,sql) -> {
            code(() -> s.getGoods("OTHER","S","G"),ErrorCode.NOT_FOUND);
            code(() -> s.getGoods("E","OTHER_STORE","G"),ErrorCode.NOT_FOUND);
            code(() -> s.getGoods("E","S","MISSING"),ErrorCode.NOT_FOUND);
            sql.update("UPDATE prd_goods SET deleted=1 WHERE goods_no='G'");
            code(() -> s.getGoods("E","S","G"),ErrorCode.NOT_FOUND);
        });
    }
    @Test void keepsInactiveGoodsReadableAsManagementFacts() {
        run((s,sql) -> {
            sql.update("UPDATE prd_goods SET on_sale=FALSE,audit_status='PENDING'");
            var r=s.getGoods("E","S","G");assertThat(r.baseOnSale()).isFalse();
            assertThat(r.auditStatus()).isEqualTo("PENDING");
            sql.update("DELETE FROM prd_sku");assertThat(s.getGoods("E","S","G").skus()).isEmpty();
        });
    }
    @Test void rejectsUnknownStoreAndRestoresCallerScope() {
        run((s,sql) -> {
            var scope=DataScopeSpec.of(ScopeDim.SELF,Set.of("BUYER"));DataScopeContext.set(scope);
            try {
                assertThat(s.getGoods("E","S","G").goodsNo()).isEqualTo("G");
                assertThat(DataScopeContext.current()).isSameAs(scope);
                code(() -> s.getGoods("E","X","G"),ErrorCode.NOT_FOUND);
                assertThat(DataScopeContext.current()).isSameAs(scope);
                sql.update("UPDATE prd_goods SET store_no=NULL");code(() -> s.getGoods("E","S","G"),ErrorCode.NOT_FOUND);
            } finally { DataScopeContext.clear(); }
        });
    }
    @Test void rejectsInvalidScopeAndBusinessKeys() {
        run((s,sql) -> {
            for(String bad:new String[]{null,""," ","X".repeat(33)}) {
                code(() -> s.getGoods(bad,"S","G"),ErrorCode.BAD_REQUEST);
                code(() -> s.getGoods("E",bad,"G"),ErrorCode.BAD_REQUEST);
                code(() -> s.getGoods("E","S",bad),ErrorCode.BAD_REQUEST);
            }
        });
    }
    @Test void batchUsesAuthoritativeStoreScopeAndOmitsUnavailableIdentities() {
        run((service, sql) -> {
            sql.update("INSERT INTO prd_goods VALUES(2,'OTHER_STORE','E','X','外店',NULL,FALSE,'PENDING',0,0),"
                    + "(3,'OTHER_ENTITY','X','S','外主体',NULL,TRUE,'APPROVED',0,0),"
                    + "(4,'DELETED','E','S','归档',NULL,TRUE,'APPROVED',0,1)");
            var facts = service.getGoodsBatch("E", "S", List.of("G", "OTHER_STORE", "OTHER_ENTITY", "DELETED", "MISSING"));
            assertThat(facts).extracting(GoodsCatalogQueryPort.GoodsSummary::goodsNo).containsExactly("G");
            assertThat(facts.getFirst().title()).isEqualTo("菜品");
            assertThat(new ObjectMapper().writeValueAsString(facts)).doesNotContain("price", "stock", "skus");
            code(() -> service.getGoodsBatch("E", "S", List.of()), ErrorCode.BAD_REQUEST);
            code(() -> service.getGoodsBatch("E", "S", List.of("G", "G")), ErrorCode.BAD_REQUEST);
            code(() -> service.getGoodsBatch("E", "S", IntStream.range(0,257)
                    .mapToObj(i -> "G" + i).toList()), ErrorCode.BAD_REQUEST);
        });
    }

    private static void code(Runnable command,ErrorCode expected) {
        assertThatThrownBy(command::run).isInstanceOfSatisfying(BizException.class,
                e -> assertThat(e.errorCode()).isEqualTo(expected));
    }
    @Configuration(proxyBeanMethods=false) @Import(PrimaryPersistenceConfiguration.class)
    static class Fixture {
        @Bean SqlSessionTemplate session(SqlSessionFactory factory) { return new SqlSessionTemplate(factory); }
        @Bean MapperFactoryBean<GoodsMapper> goods(SqlSessionTemplate session) {
            var m=new MapperFactoryBean<>(GoodsMapper.class);m.setSqlSessionTemplate(session);return m;
        }
        @Bean MapperFactoryBean<SkuMapper> skus(SqlSessionTemplate session) {
            var m=new MapperFactoryBean<>(SkuMapper.class);m.setSqlSessionTemplate(session);return m;
        }
        @Bean GoodsCatalogQueryPort catalog(GoodsMapper goods,SkuMapper skus) { return new GoodsCatalogQueryPortImpl(goods,skus); }
    }
}
