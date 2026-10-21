package ai.neargo.shop.media;

import ai.neargo.shop.media.port.MediaAssetQueryPortImpl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.config.PrimaryPersistenceConfiguration;
import java.util.List;
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

class MediaAssetQueryPortTest {
    @Test void queriesRealOwnershipUsageAndStatusWithoutAcceptingForeignOrPurgedAssets() {
        new ApplicationContextRunner().withUserConfiguration(Fixture.class).withPropertyValues(
                "spring.datasource.url=jdbc:h2:mem:media_" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE",
                "spring.datasource.username=sa", "spring.datasource.password=").run(c -> {
            var sql = new JdbcTemplate(c.getBean(DataSource.class));
            sql.execute("CREATE TABLE sys_media_asset(id BIGINT PRIMARY KEY,asset_key VARCHAR(512),"
                    + "entity_no VARCHAR(32),store_no VARCHAR(32),biz_type VARCHAR(32),"
                            + "status VARCHAR(32),purge_batch_no VARCHAR(32))");
            sql.update("INSERT INTO sys_media_asset VALUES(1,'E/S/goods/a.jpg','E','S','GOODS','ACTIVE',NULL),"
                    + "(2,'E/S/goods/b.jpg','E','S','GOODS','RECLAIMABLE',NULL),"
                    + "(3,'E/S/goods/c.jpg','OTHER','S','GOODS','ACTIVE',NULL),"
                    + "(4,'E/S/goods/d.jpg','E','OTHER','GOODS','ACTIVE',NULL),"
                    + "(5,'E/S/goods/e.jpg','E','S','QUAL','ACTIVE',NULL),"
                    + "(6,'E/S/goods/f.jpg','E','S','GOODS','PURGED',NULL),"
                    + "(7,'E/S/goods/g.jpg','E','S','GOODS','PENDING',NULL),"
                    + "(8,'E/S/goods/h.jpg','E','S','GOODS','ACTIVE','PURGE')");
            var port = c.getBean(MediaAssetQueryPortImpl.class);
            assertThat(port.getAssets("E", "S", List.of("E/S/goods/a.jpg", "E/S/goods/b.jpg")))
                    .extracting(f -> f.assetKey()).containsExactly("E/S/goods/a.jpg");
            for (String key : List.of("b", "c", "d", "e", "f", "g", "h", "missing")) {
                assertThat(port.getAssets("E", "S", List.of("E/S/goods/" + key + ".jpg"))).isEmpty();
            }
            assertThatThrownBy(() -> port.getAssets("E", "S", List.of("https://foreign/x.jpg")))
                    .isInstanceOf(BizException.class);
            assertThatThrownBy(() -> port.getAssets("E", "S", List.of("E/S/goods/a.jpg", "E/S/goods/a.jpg")))
                    .isInstanceOf(BizException.class);
        });
    }

    @Test void registersPresentationAndHistoryEvenWhenFoodRunsInAnotherHost() {
        new ApplicationContextRunner().withUserConfiguration(Fixture.class).withPropertyValues(
                "spring.datasource.url=jdbc:h2:mem:refs_" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE",
                "spring.datasource.username=sa", "spring.datasource.password=").run(c -> {
            var source = new FoodPresentationMediaRefs(c.getBean(DataSource.class));
            assertThat(source.columns()).isEmpty();
            var sql = new JdbcTemplate(c.getBean(DataSource.class));
            sql.execute("CREATE TABLE fnbXdish(goods_no VARCHAR(32),presentation TEXT)");
            assertThat(source.columns()).isEmpty();
            sql.execute("CREATE TABLE fnb_dish(goods_no VARCHAR(32),presentation TEXT)");
            sql.execute("CREATE TABLE fnb_config_revision(revision_no VARCHAR(32),definition_snapshot TEXT)");
            assertThat(source.columns()).extracting(MediaRefColumn::column)
                    .containsExactly("presentation", "definition_snapshot");
            sql.update("INSERT INTO fnb_dish VALUES('G','{\"imageRefs\":[\"E/S/goods/a.jpg\"]}')");
            sql.update("INSERT INTO fnb_config_revision VALUES('R','{\"imageRefs\":[\"E/S/goods/history.jpg\"]}')");
            var mapper = c.getBean(MediaRefMapper.class);
            assertThat(source.columns().stream().flatMap(column -> mapper.scanColumn(column.table(),
                    column.column(), column.keyColumn()).stream()).flatMap(row ->
                            MediaKeys.extract(row.get("val").toString()).stream()).toList())
                    .containsExactly("E/S/goods/a.jpg", "E/S/goods/history.jpg");
        });
    }

    @Configuration(proxyBeanMethods = false)
    @Import(PrimaryPersistenceConfiguration.class)
    static class Fixture {
        @Bean SqlSessionTemplate session(SqlSessionFactory factory) { return new SqlSessionTemplate(factory); }
        @Bean MapperFactoryBean<SysMediaAssetMapper> assets(SqlSessionTemplate session) {
            var mapper = new MapperFactoryBean<>(SysMediaAssetMapper.class);
            mapper.setSqlSessionTemplate(session); return mapper;
        }
        @Bean MapperFactoryBean<MediaRefMapper> references(SqlSessionTemplate session) {
            var mapper = new MapperFactoryBean<>(MediaRefMapper.class);
            mapper.setSqlSessionTemplate(session); return mapper;
        }
        @Bean MediaAssetQueryPortImpl media(SysMediaAssetMapper assets) { return new MediaAssetQueryPortImpl(assets); }
    }
}
