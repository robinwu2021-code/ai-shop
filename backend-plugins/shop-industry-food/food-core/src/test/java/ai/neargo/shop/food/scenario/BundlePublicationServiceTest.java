package ai.neargo.shop.food.scenario;

import static ai.neargo.shop.food.scenario.BundleServiceTest.choice;
import static ai.neargo.shop.food.scenario.BundleServiceTest.group;
import static ai.neargo.shop.food.scenario.BundleServiceTest.n;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.event.outbox.OutboxPublisher;
import ai.neargo.shop.event.outbox.runtime.UnifiedOutboxFacade;
import ai.neargo.shop.food.dto.BundleChoiceInput;
import ai.neargo.shop.food.dto.BundleGroupInput;
import ai.neargo.shop.food.dto.CreateBundleReq;
import ai.neargo.shop.food.dto.ListConfigRevisionsQuery;
import ai.neargo.shop.food.dto.PublishConfigReq;
import ai.neargo.shop.food.dto.ReplaceBundleDefinitionReq;
import ai.neargo.shop.food.mapper.FoodMappers.ActionLogMapper;
import ai.neargo.shop.food.mapper.FoodMappers.BundleChoiceMapper;
import ai.neargo.shop.food.mapper.FoodMappers.BundleGroupMapper;
import ai.neargo.shop.food.mapper.FoodMappers.BundleMapper;
import ai.neargo.shop.food.mapper.FoodMappers.ConfigRevisionMapper;
import ai.neargo.shop.food.mapper.FoodMappers.ModifierComponentMapper;
import ai.neargo.shop.food.mapper.FoodMappers.ModifierMapper;
import ai.neargo.shop.food.mapper.FoodMappers.OptionGroupItemMapper;
import ai.neargo.shop.food.mapper.FoodMappers.OptionGroupMapper;
import ai.neargo.shop.food.policy.BundlePublicationSource;
import ai.neargo.shop.food.policy.PublicationDocuments;
import ai.neargo.shop.food.service.BundleService;
import ai.neargo.shop.food.service.ConfigRevisionService;
import ai.neargo.shop.food.service.impl.ConfigRevisionServiceImpl;
import ai.neargo.shop.idem.IdempotencyService;
import ai.neargo.shop.spi.product.GoodsCatalogQueryPort;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

/** Real Mappers prove immutable bundle contents, local atomicity, old-reader compatibility and recovery. */
class BundlePublicationServiceTest extends FoodWriteGuardFixture {
    static final OffsetDateTime NOW = ConfigRevisionServiceTest.NOW;
    void run(BiConsumer<AssertableApplicationContext,Fixture> test) {
        runner().withUserConfiguration(StorePolicyPublicationTest.Publication.class,
                OptionGroupServiceTest.Groups.class, ModifierServiceTest.Modifiers.class,
                BundleServiceTest.Bundles.class, Publications.class).run(c -> {
                    var f = new BundleServiceTest().fixture(c);
                    f.sql().execute("CREATE TABLE fnb_config_revision (id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                            + "tenant_no VARCHAR(32) DEFAULT 'MAIN',entity_no VARCHAR(32),created_at DATETIME(3),"
                            + "created_by VARCHAR(32),updated_at DATETIME(3),updated_by VARCHAR(32),"
                            + "version BIGINT DEFAULT 0,deleted INT DEFAULT 0,revision_no VARCHAR(32) UNIQUE,"
                            + "config_kind VARCHAR(24),config_no VARCHAR(32),"
                            + "revision BIGINT,content_schema INT,definition_snapshot TEXT,content_hash VARCHAR(64),"
                            + "effective_at DATETIME(3),status VARCHAR(16),previous_revision_no VARCHAR(32),"
                            + "scope_store_nos TEXT,"
                            + "UNIQUE(entity_no,config_kind,config_no,revision))");
                    c.getBean(UnifiedOutboxFacade.class).setArchiveOnly(Set.of(
                            "PLATFORM:FOOD_BUNDLE_CHANGED", "PLATFORM:FOOD_CONFIG_PUBLISHED"));
                    test.accept(c, f);
                });
    }
    void prepared(AssertableApplicationContext c) {
        var s = c.getBean(BundleService.class);
        s.createBundle("E", "A", "actor", new CreateBundleReq("MEAL"), "create");
        s.replaceDefinition("E", "A", "MEAL", "actor", BundleServiceTest.fixed(0), "define");
    }
    PublishConfigReq req(long v) { return new PublishConfigReq(v, NOW, null); }

    @Test void freezesCompleteDefinitionAndKeepsDefinitionCounterSeparateFromPublication() {
        run((c,f) -> {
            prepared(c); var s = c.getBean(ConfigRevisionService.class);
            var first = s.publishBundle("E", "A", "MEAL", "actor", req(1), "publish").publication();
            assertThat(first.configKind()).isEqualTo("BUNDLE"); assertThat(first.revision()).isEqualTo(1);
            var frozen = s.getRevision("E", "A", first.revisionNo()).definitionSnapshot();
            assertThat(frozen).contains("\"contentSchema\":2", "\"unitsPerPick\":\"1.5\"", "SIDE", "skuFacts");
            var working = c.getBean(BundleService.class).getBundle("E", "A", "MEAL").config();
            assertThat(working.version()).isEqualTo(2); assertThat(working.data().configVersion()).isEqualTo(1);
            assertThat(working.data().publishedRevisionNo()).isEqualTo(first.revisionNo());
            c.getBean(BundleService.class).replaceDefinition("E", "A", "MEAL", "actor",
                    new ReplaceBundleDefinitionReq(2L, List.of(), List.of()), "clear");
            c.getBean(Sources.class).outage.set(true);
            assertThat(s.publishBundle("E", "A", "MEAL", "actor", req(1), "publish").publication()).isEqualTo(first);
            assertThat(s.getRevision("E", "A", first.revisionNo()).definitionSnapshot()).isEqualTo(frozen);
            code(() -> s.publishBundle("E", "A", "MEAL", "other", req(1), "publish"), ErrorCode.NOT_FOUND);
            code(() -> s.publishBundle("E", "A", "MEAL", "actor", req(2), "publish"), ErrorCode.CONFLICT);
            assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM fnb_config_revision", Integer.class)).isOne();
        });
    }
    @Test void futureVersionsAndSharedHeadPointerNeverBypassStoreTimeResolution() {
        run((c,f) -> {
            prepared(c); var s=c.getBean(ConfigRevisionService.class);
            var a=s.publishBundle("E","A","MEAL","actor",req(1),"a").publication();
            var future=s.publishBundle("E","A","MEAL","actor",
                    new PublishConfigReq(2L,NOW.plusHours(1),null),"future").publication();
            var b=s.publishBundle("E","B","MEAL","actor",req(3),"b").publication();
            assertThat(future.status()).isEqualTo("SCHEDULED");
            assertThat(s.resolveRevision("E","A","BUNDLE","MEAL",NOW).publication()).isEqualTo(a);
            assertThat(s.resolveRevision("E","B","BUNDLE","MEAL",NOW).publication()).isEqualTo(b);
            assertThat(s.resolveRevision("E","A","BUNDLE","MEAL",NOW.plusHours(1))
                    .publication().revisionNo()).isEqualTo(future.revisionNo());
            code(() -> s.getRevision("E","B",a.revisionNo()), ErrorCode.NOT_FOUND);
            code(() -> s.getRevision("OTHER","A",a.revisionNo()), ErrorCode.NOT_FOUND);
            assertThat(s.listRevisions("E","A",new ListConfigRevisionsQuery("BUNDLE","MEAL",1,20)).total())
                    .isEqualTo(2);
            assertThat(f.sql().queryForObject("SELECT status FROM fnb_config_revision WHERE revision_no=?",
                    String.class,future.revisionNo())).isEqualTo("SCHEDULED");
        });
    }
    @Test void rejectsEmptyZeroCapacityAndNestedDefinitionsWithoutDurablePublication() {
        run((c,f) -> {
            var b=c.getBean(BundleService.class); var s=c.getBean(ConfigRevisionService.class);
            b.createBundle("E","A","actor",new CreateBundleReq("MEAL"),"create");
            code(() -> s.publishBundle("E","A","MEAL","actor",req(0),"empty"),ErrorCode.BAD_REQUEST);
            b.replaceDefinition("E","A","MEAL","actor",new ReplaceBundleDefinitionReq(0L,
                    List.of(group("G1","0","0",null)),List.of(choice("C1","G1","SIDE","INCLUDED","0","1","0"))),"zero");
            code(() -> s.publishBundle("E","A","MEAL","actor",req(1),"zero-pub"),ErrorCode.BAD_REQUEST);
            f.sql().update("UPDATE fnb_bundle_group SET max_qty=1");
            f.sql().update("INSERT INTO fnb_bundle(entity_no,sku_no,config_version,status) "
                    + "VALUES('E','SIDE',0,'DRAFT')");
            code(() -> s.publishBundle("E","A","MEAL","actor",req(1),"nested"),ErrorCode.BAD_REQUEST);
            assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM fnb_config_revision",Integer.class)).isZero();
        });
    }
    @Test void rereadsWholeDefinitionAfterRemoteAuthorityEvenWhenHeadVersionWasNotBumped() {
        run((c,f) -> {
            prepared(c); var control=c.getBean(Sources.class);
            control.duringSku=() -> f.sql().update("UPDATE fnb_bundle_group SET name='raced'");
            code(() -> c.getBean(ConfigRevisionService.class).publishBundle("E","A","MEAL","actor",req(1),"race"),
                    ErrorCode.CONFLICT);
            assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM fnb_config_revision",Integer.class)).isZero();
            control.duringSku=() -> { }; control.bad=true;
            assertThatThrownBy(() -> c.getBean(ConfigRevisionService.class)
                    .publishBundle("E","A","MEAL","actor",req(1),"bad-fact"))
                    .isInstanceOf(IllegalStateException.class);
        });
    }
    @Test void publisherFailureRollsBackRevisionHeadAuditOutboxAndReceipt() {
        run((c,f) -> {
            prepared(c); c.getBean(Sources.class).fail.set(true);
            assertThatThrownBy(() -> c.getBean(ConfigRevisionService.class)
                    .publishBundle("E","A","MEAL","actor",req(1),"fail"))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM fnb_config_revision",Integer.class)).isZero();
            var v=c.getBean(BundleService.class).getBundle("E","A","MEAL").config();
            assertThat(v.version()).isEqualTo(1); assertThat(v.data().configVersion()).isEqualTo(1);
            assertThat(v.data().publishedRevisionNo()).isNull();
            for(String t:List.of("sys_idempotent","sys_outbox","fnb_action_log"))
                assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM "+t,Integer.class)).as(t).isEqualTo(2);
        });
    }
    @Test void disabledWriterStillReadsAndReplaysButDoesNotStartNewPublication() {
        run((c,f) -> {
            prepared(c); var s=c.getBean(ConfigRevisionService.class); var control=c.getBean(Sources.class);
            control.enabled.set(false);
            code(() -> s.publishBundle("E","A","MEAL","actor",req(1),"disabled"),ErrorCode.NOT_FOUND);
            control.enabled.set(true);
            var one=s.publishBundle("E","A","MEAL","actor",req(1),"one");
            control.enabled.set(false); control.outage.set(true);
            assertThat(s.publishBundle("E","A","MEAL","actor",req(1),"one")).isEqualTo(one);
            assertThat(s.resolveRevision("E","A","BUNDLE","MEAL",NOW).publication()).isEqualTo(one.publication());
            code(() -> s.publishBundle("E","A","MEAL","actor",req(2),"disabled2"),ErrorCode.NOT_FOUND);
            assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM fnb_config_revision",Integer.class)).isOne();
        });
    }
    @Test void exactSchemaTwoQuantitiesSurviveJsonReformattingAndRejectUnknownSchemaOrTampering() {
        run((c,f) -> {
            var b=c.getBean(BundleService.class); var s=c.getBean(ConfigRevisionService.class);
            b.createBundle("E","A","actor",new CreateBundleReq("MEAL"),"a");
            b.replaceDefinition("E","A","MEAL","actor",new ReplaceBundleDefinitionReq(0L,
                    List.of(new BundleGroupInput("G","配菜",n("0"),n("999999999999.999999"),"BASE",0)),
                    List.of(new BundleChoiceInput("C","G","SIDE",n("999999999.999"),"DIFF_BASE",n("0"),
                            n("0"),n("999999999999.999999"),0))),"b");
            var pub=s.publishBundle("E","A","MEAL","actor",req(1),"p").publication();
            String original=s.getRevision("E","A",pub.revisionNo()).definitionSnapshot();
            assertThat(original).contains("\"maxQty\":\"999999999999.999999\"", "\"unitsPerPick\":\"999999999.999\"");
            var json=c.getBean(ObjectMapper.class);
            Map<?,?> sorted=json.readValue(original,TreeMap.class);
            String reordered=json.writerWithDefaultPrettyPrinter().writeValueAsString(sorted);
            f.sql().update("UPDATE fnb_config_revision SET definition_snapshot=?",reordered);
            assertThat(s.getRevision("E","A",pub.revisionNo()).definitionSnapshot()).isEqualTo(reordered);
            String unknown=original.replace("\"contentSchema\":2","\"contentSchema\":9");
            f.sql().update("UPDATE fnb_config_revision SET content_schema=9,definition_snapshot=?,content_hash=?",
                    unknown,PublicationDocuments.hash(json,unknown));
            assertThatThrownBy(() -> s.getRevision("E","A",pub.revisionNo())).isInstanceOf(IllegalStateException.class);
            f.sql().update("UPDATE fnb_config_revision SET content_schema=2,definition_snapshot=?,content_hash=?",
                    original.replace("SIDE", "FORGED"), PublicationDocuments.hash(json, original));
            assertThatThrownBy(() -> s.getRevision("E","A",pub.revisionNo())).isInstanceOf(IllegalStateException.class);
        });
    }
    @Test void cancelledBundleRemainsAuditableAndFallsBackToPreviousEffectiveVersion() {
        run((c,f) -> {
            prepared(c); var s=c.getBean(ConfigRevisionService.class);
            var first=s.publishBundle("E","A","MEAL","actor",req(1),"a").publication();
            var second=s.publishBundle("E","A","MEAL","actor",req(2),"b").publication();
            f.sql().update("UPDATE fnb_config_revision SET status='CANCELLED' WHERE revision_no=?",second.revisionNo());
            assertThat(s.getRevision("E","A",second.revisionNo()).publication().status()).isEqualTo("CANCELLED");
            assertThat(s.listRevisions("E","A",new ListConfigRevisionsQuery("BUNDLE","MEAL",1,20)).total())
                    .isEqualTo(2);
            assertThat(s.resolveRevision("E","A","BUNDLE","MEAL",NOW).publication()).isEqualTo(first);
        });
    }
    private static void code(Runnable call,ErrorCode expected) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(BizException.class,
                e -> assertThat(e.errorCode()).isEqualTo(expected));
    }
    static class Sources {
        final AtomicBoolean enabled=new AtomicBoolean(true),fail=new AtomicBoolean(),outage=new AtomicBoolean();
        Runnable duringSku=() -> { }; boolean bad;
    }
    @Configuration(proxyBeanMethods=false)
    static class Publications {
        @Bean Sources bundlePublicationSources() { return new Sources(); }
        @Bean MapperFactoryBean<ConfigRevisionMapper> bundleRevisions(
                @Qualifier("foodSqlSessionTemplate") SqlSessionTemplate template) {
            var b=new MapperFactoryBean<>(ConfigRevisionMapper.class);b.setSqlSessionTemplate(template);return b;
        }
        @Bean ConfigRevisionService publications(ConfigRevisionMapper revisions,ModifierMapper modifiers,
                ModifierComponentMapper components,OptionGroupMapper groups,OptionGroupItemMapper items,
                BundleMapper bundles,BundleGroupMapper bundleGroups,BundleChoiceMapper choices,ActionLogMapper actions,
                IdempotencyService idem,@Qualifier("foodOutboxPublisher") OutboxPublisher outbox,ObjectMapper json,
                PlatformTransactionManager manager,OptionGroupServiceTest.Sources specs,Sources control) {
            var goods=mock(GoodsCatalogQueryPort.class);
            when(goods.getSku(anyString(),anyString(),anyString())).thenAnswer(i -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                if(control.outage.get())throw new IllegalStateException("authority unavailable");
                control.duringSku.run();
                return new GoodsCatalogQueryPort.SkuIdentityFact(i.getArgument(2),"G",
                        control.bad?"OTHER":i.getArgument(0),i.getArgument(1),0);
            });
            OutboxPublisher checked=(event,options) -> {
                var receipt=outbox.publish(event,options);
                if(control.fail.get())throw new IllegalStateException("after-publication");return receipt;
            };
            return new ConfigRevisionServiceImpl(revisions,modifiers,components,groups,items,actions,idem,
                    checked,json,manager,goods,specs,Clock.fixed(Instant.parse("2026-10-21T08:00:00Z"),ZoneOffset.UTC),
                    new BundlePublicationSource(bundles,bundleGroups,choices,control.enabled::get));
        }
    }
}
