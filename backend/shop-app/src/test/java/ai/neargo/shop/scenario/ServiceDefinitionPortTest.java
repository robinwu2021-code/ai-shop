package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.config.PrimaryPersistenceConfiguration;
import ai.neargo.shop.product.entity.PrdGoods;
import ai.neargo.shop.product.mapper.ProductMappers.FulfillmentRequirementMapper;
import ai.neargo.shop.product.mapper.ProductMappers.GoodsMapper;
import ai.neargo.shop.product.mapper.ProductMappers.ServiceDefinitionMapper;
import ai.neargo.shop.product.mapper.ProductMappers.SkuMapper;
import ai.neargo.shop.product.port.ServiceDefinitionPortImpl;
import ai.neargo.shop.product.port.ServiceGoods;
import ai.neargo.shop.product.port.ServiceProductFactsPortImpl;
import ai.neargo.shop.product.service.MerchantGoodsService;
import ai.neargo.shop.product.service.impl.MerchantGoodsServiceImpl;
import ai.neargo.shop.spi.product.GoodsQueryPort;
import ai.neargo.shop.spi.product.ServiceDefinitionPort;
import ai.neargo.shop.spi.product.ServiceDefinitionPort.Publish;
import ai.neargo.shop.spi.product.ServiceDefinitionPort.Requirement;
import ai.neargo.shop.spi.product.ServiceDefinitionPort.Step;
import ai.neargo.shop.spi.product.ServiceDefinitionPort.Timing;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.objenesis.ObjenesisStd;
import tools.jackson.databind.json.JsonMapper;

/**
 * 服务定义端口（TDD-美业域迁移新库 §2.8 P3）：在门店交易库定稿结构（{@code schema-product-test.sql}）上跑真实 SQL。
 * 发布、版本冲突、按 SKU 覆盖、重放、时长镜像不动商品版本、服务事实按 SKU 取时长、商家保存不写时长。
 */
class ServiceDefinitionPortTest {
    private static final List<Step> STEPS = List.of(new Step("SERVICE", 0));
    private static final Requirement THERAPIST = new Requirement(null, "THERAPIST", null, "G", 1, 0, 0, null, "ANY_QUALIFIED");
    private static final Requirement BED = new Requirement(null, null, "BED", null, 1, 1, 0, null, "ANY_QUALIFIED");

    record Ports(ServiceDefinitionPort definitions, ServiceProductFactsPortImpl facts, JdbcTemplate sql,
            ServiceDefinitionMapper mapper, GoodsMapper goods) { }

    private void run(BiConsumer<Ports, JdbcTemplate> body) {
        new ApplicationContextRunner()
                .withUserConfiguration(PrimaryPersistenceConfiguration.class, ai.neargo.shop.common.AuditMetaObjectHandler.class)
                .withPropertyValues("spring.datasource.url=jdbc:h2:mem:svcdef_" + UUID.randomUUID()
                        + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                        "spring.datasource.username=sa", "spring.datasource.password=")
                .run(context -> {
                    var source = context.getBean(DataSource.class);
                    try (var connection = source.getConnection()) {
                        ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema-product-test.sql"));
                    }
                    var sql = new JdbcTemplate(source);
                    sql.update("INSERT INTO prd_goods (goods_no, entity_no, store_no, type, duration_min, version) "
                            + "VALUES ('G', 'E', 'S', 'SERVICE', 45, 7)");
                    sql.update("INSERT INTO prd_sku (sku_no, goods_no, entity_no, market) VALUES "
                            + "('S1', 'G', 'E', 'CN'), ('S2', 'G', 'E', 'CN')");
                    var factory = context.getBean(SqlSessionFactory.class);
                    for (var type : List.of(SkuMapper.class, GoodsMapper.class, ServiceDefinitionMapper.class,
                            FulfillmentRequirementMapper.class)) {
                        factory.getConfiguration().addMapper(type);
                    }
                    var template = new SqlSessionTemplate(factory);
                    var lookup = new ServiceGoods(template.getMapper(SkuMapper.class),
                            template.getMapper(GoodsMapper.class));
                    var mapper = template.getMapper(ServiceDefinitionMapper.class);
                    var port = new ServiceDefinitionPortImpl(lookup, mapper,
                            template.getMapper(FulfillmentRequirementMapper.class),
                            template.getMapper(GoodsMapper.class), JsonMapper.builder().build());
                    var quotes = mock(GoodsQueryPort.class);
                    for (var sku : List.of("S1", "S2")) {
                        when(quotes.snapshot(List.of(sku), Map.of("E", "S"))).thenReturn(Map.of(sku,
                                new GoodsQueryPort.SkuSnapshot(sku, "G", "E", "service", null, null, "SERVICE", null,
                                        9900, 0, true, List.of("SERVICE"), null, null, "NORMAL", null, null, "S")));
                    }
                    body.accept(new Ports(port, new ServiceProductFactsPortImpl(lookup, mapper, quotes), sql, mapper,
                            template.getMapper(GoodsMapper.class)),
                            sql);
                });
    }

    private static Publish defaults(int expected, int minutes, String ref) {
        return new Publish("E", "S", "S1", false, expected, new Timing(minutes, 5, 10), STEPS,
                List.of("空腹勿做"), List.of(THERAPIST, BED), ref, "staff-1");
    }

    private static Publish override(int expected, int minutes, List<Requirement> requirements, String ref) {
        return new Publish("E", "S", "S2", true, expected, new Timing(minutes, 0, 15), null, null, requirements,
                ref, "staff-1");
    }

    @Test
    void publishesDefaultAndMirrorsDurationWithoutBumpingGoodsVersion() {
        run((p, sql) -> {
            var published = p.definitions().publish(defaults(0, 60, "REQ-1"));
            assertThat(published.definitionVersion()).isEqualTo(1);
            assertThat(published.skuNo()).isEqualTo(ServiceDefinitionPort.DEFAULT_SKU);
            assertThat(published.timing()).isEqualTo(new Timing(60, 5, 10));
            assertThat(plain(published.requirements())).containsExactly(THERAPIST, BED);
            assertThat(published.status()).isEqualTo(ServiceDefinitionPort.ACTIVE);
            assertThat(published.snapshotHash()).hasSize(64);
            assertThat(sql.queryForMap("SELECT duration_min, version FROM prd_goods WHERE goods_no='G'"))
                    .containsEntry("duration_min", 60).containsEntry("version", 7L);
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM prd_fulfillment_requirement "
                    + "WHERE goods_no='G' AND definition_version=1 AND revision_no IS NULL", Integer.class))
                    .isEqualTo(2);
            var current = p.definitions().current("E", "S", "S2");
            assertThat(current.override()).isFalse();
            assertThat(current.timing().durationMin()).isEqualTo(60);
            assertThat(current.notes()).containsExactly("空腹勿做");
        });
    }

    @Test
    void wholeRowUpdateNeverWritesBackStaleDuration() {
        run((p, sql) -> {
            var goods = p.goods();
            var stale = goods.selectOne(com.baomidou.mybatisplus.core.toolkit.Wrappers.<PrdGoods>lambdaQuery()
                    .eq(PrdGoods::getGoodsNo, "G"));
            assertThat(stale.getDurationMin()).isEqualTo(45);
            p.definitions().publish(defaults(0, 60, null));
            stale.setOnSale(true);
            goods.updateById(stale);
            assertThat(sql.queryForMap("SELECT duration_min, version FROM prd_goods WHERE goods_no='G'"))
                    .as("发布前读出的整行做一次上下架：乐观锁照过，时长仍是镜像过来的 60")
                    .containsEntry("duration_min", 60).containsEntry("version", 8L);
        });
    }

    @Test
    void rejectsStaleExpectedVersion() {
        run((p, sql) -> {
            p.definitions().publish(defaults(0, 60, null));
            assertThatThrownBy(() -> p.definitions().publish(defaults(0, 70, null)))
                    .isInstanceOfSatisfying(BizException.class,
                            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.CONFLICT));
            assertThat(p.definitions().publish(defaults(1, 70, null)).definitionVersion()).isEqualTo(2);
        });
    }

    @Test
    void overrideNeedsDefaultAndOnlyChangesItsSku() {
        run((p, sql) -> {
            assertThatThrownBy(() -> p.definitions().publish(override(0, 90, null, null)))
                    .isInstanceOf(BizException.class);
            p.definitions().publish(defaults(0, 60, null));
            var s2 = p.definitions().publish(override(1, 90, List.of(BED), null));
            assertThat(s2.definitionVersion()).isEqualTo(2);
            assertThat(s2.override()).isTrue();
            assertThat(s2.timing()).isEqualTo(new Timing(90, 0, 15));
            assertThat(s2.steps()).isEqualTo(STEPS);
            assertThat(s2.notes()).containsExactly("空腹勿做");
            assertThat(plain(s2.requirements())).containsExactly(BED);
            var s1 = p.definitions().current("E", "S", "S1");
            assertThat(s1.definitionVersion()).isEqualTo(2);
            assertThat(s1.override()).isFalse();
            assertThat(plain(s1.requirements())).containsExactly(THERAPIST, BED);
            assertThat(sql.queryForObject("SELECT duration_min FROM prd_goods WHERE goods_no='G'", Integer.class))
                    .as("覆盖行不改商品时长").isEqualTo(60);
            assertThat(p.facts().get("E", "S", "S2").durationMin()).isEqualTo(90);
            assertThat(p.facts().get("E", "S", "S1").durationMin()).isEqualTo(60);
            assertThat(p.definitions().byVersion("E", "S", "S2", 1).timing().durationMin()).isEqualTo(60);
            assertThat(p.definitions().byVersion("E", "S", "S2", 3)).isNull();
            // 再发默认行：覆盖行与它的要求原样带到第 3 版
            p.definitions().publish(defaults(2, 50, null));
            var carried = p.definitions().current("E", "S", "S2");
            assertThat(carried.definitionVersion()).isEqualTo(3);
            assertThat(carried.timing().durationMin()).isEqualTo(90);
            assertThat(plain(carried.requirements())).containsExactly(BED);
            assertThat(p.definitions().list("E", "S", Instant.now().plusSeconds(1)))
                    .extracting(ServiceDefinitionPort.Definition::skuNo).containsExactly("", "S2");
        });
    }

    @Test
    void replayReturnsTheOriginalVersion() {
        run((p, sql) -> {
            var first = p.definitions().publish(defaults(0, 60, "REQ-1"));
            var replay = p.definitions().publish(defaults(0, 60, "REQ-1"));
            assertThat(replay).isEqualTo(first);
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM prd_service_definition", Integer.class)).isEqualTo(1);
        });
    }

    @Test
    void factsFallBackToGoodsDurationWithoutDefinition() {
        run((p, sql) -> assertThat(p.facts().get("E", "S", "S1").durationMin()).isEqualTo(45));
    }

    @Test
    void merchantSaveLeavesDefinedDurationAlone() throws Exception {
        run((p, sql) -> {
            try {
                var service = new ObjenesisStd().newInstance(MerchantGoodsServiceImpl.class);
                service.setServiceDefinitions(p.mapper());
                var apply = MerchantGoodsServiceImpl.class.getDeclaredMethod("applyOptional", PrdGoods.class,
                        MerchantGoodsService.SaveCommand.class);
                apply.setAccessible(true);
                var goods = new PrdGoods();
                goods.setGoodsNo("G");
                goods.setType(PrdGoods.TYPE_SERVICE);
                goods.setDurationMin(45);
                apply.invoke(service, goods, command(new MerchantGoodsService.ServiceSpec(30, null)));
                assertThat(goods.getDurationMin()).as("还没有服务定义：商家照常改时长").isEqualTo(30);
                p.definitions().publish(defaults(0, 60, null));
                goods.setDurationMin(60);
                apply.invoke(service, goods, command(new MerchantGoodsService.ServiceSpec(30, null)));
                assertThat(goods.getDurationMin()).as("有服务定义：时长只由定义发布写").isEqualTo(60);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        });
    }

    /** 去掉读出时才有的要求编号，便于与发布的输入比较。 */
    private static List<Requirement> plain(List<Requirement> requirements) {
        assertThat(requirements).allSatisfy(r -> assertThat(r.requirementNo()).startsWith("PFQ"));
        return requirements.stream().map(r -> new Requirement(null, r.roleCode(), r.resourceType(), r.skillGoodsNo(),
                r.qty(), r.sequenceNo(), r.offsetMin(), r.durationMin(), r.selectionRule())).toList();
    }

    @Test
    void perSkuPublishLetsProductChooseDefaultOrOverride() {
        run((p, sql) -> {
            var first = p.definitions().publish(perSku("S1", 0, 60, "A"));
            assertThat(first.override()).as("还没有定义：写默认行").isFalse();
            var second = p.definitions().publish(perSku("S2", 1, 90, "B"));
            assertThat(second.override()).as("两个 SKU：写这个 SKU 的覆盖行").isTrue();
            assertThat(p.definitions().publish(perSku("S2", 1, 90, "B")).definitionVersion())
                    .as("重放：版本已变也按操作号回到原版本").isEqualTo(2);
            sql.update("DELETE FROM prd_sku WHERE sku_no='S2'");
            var single = p.definitions().publish(perSku("S1", 2, 50, "C"));
            assertThat(single.override()).as("只剩一个 SKU：改默认行").isFalse();
            assertThat(sql.queryForObject("SELECT duration_min FROM prd_goods WHERE goods_no='G'", Integer.class))
                    .isEqualTo(50);
        });
    }

    @Test
    void perSkuDefaultDropsTheRemainingSkuOwnOverride() {
        run((p, sql) -> {
            p.definitions().publish(perSku("S1", 0, 60, null));
            assertThat(p.definitions().publish(perSku("S2", 1, 90, null)).override()).isTrue();
            sql.update("UPDATE prd_sku SET deleted=1 WHERE sku_no='S1'");
            var s2 = p.definitions().publish(perSku("S2", 2, 75, null));
            assertThat(s2.override()).as("只剩 S2：写默认行，它的旧覆盖行不带进新版本").isFalse();
            assertThat(s2.timing().durationMin()).isEqualTo(75);
            assertThat(p.definitions().current("E", "S", "S2").timing().durationMin()).isEqualTo(75);
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM prd_service_definition WHERE definition_version=3",
                    Integer.class)).isOne();
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM prd_fulfillment_requirement "
                    + "WHERE definition_version=3 AND sku_no IS NOT NULL", Integer.class)).isZero();
            assertThat(sql.queryForObject("SELECT duration_min FROM prd_goods WHERE goods_no='G'", Integer.class))
                    .isEqualTo(75);
        });
    }

    private static Publish perSku(String sku, int expected, int minutes, String ref) {
        return new Publish("E", "S", sku, null, expected, new Timing(minutes, 0, 0), STEPS, List.of(),
                List.of(BED), ref, "staff-1");
    }

    /** 只带服务规格的保存命令（其余字段为空）。 */
    private static MerchantGoodsService.SaveCommand command(MerchantGoodsService.ServiceSpec spec)
            throws ReflectiveOperationException {
        var components = MerchantGoodsService.SaveCommand.class.getRecordComponents();
        var types = new Class<?>[components.length];
        var values = new Object[components.length];
        for (int i = 0; i < components.length; i++) {
            types[i] = components[i].getType();
            if (components[i].getName().equals("service")) { values[i] = spec; }
            else if (types[i] == boolean.class) { values[i] = false; }
            else if (types[i] == int.class) { values[i] = 0; }
            else if (types[i] == long.class) { values[i] = 0L; }
        }
        return MerchantGoodsService.SaveCommand.class.getDeclaredConstructor(types).newInstance(values);
    }
}
