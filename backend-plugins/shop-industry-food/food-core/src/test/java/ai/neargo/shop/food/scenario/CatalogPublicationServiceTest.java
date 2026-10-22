package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.dto.DishPresentation;
import ai.neargo.shop.food.dto.DishVO;
import ai.neargo.shop.food.dto.MeasurementPolicy;
import ai.neargo.shop.food.dto.MenuCategoryInput;
import ai.neargo.shop.food.dto.MenuChannelBindingVO;
import ai.neargo.shop.food.dto.MenuCompositionVO;
import ai.neargo.shop.food.dto.MenuConfigResultVO;
import ai.neargo.shop.food.dto.MenuContextInput;
import ai.neargo.shop.food.dto.MenuItemInput;
import ai.neargo.shop.food.dto.MenuPublicationSnapshot;
import ai.neargo.shop.food.dto.MenuScheduleVO;
import ai.neargo.shop.food.dto.MenuVO;
import ai.neargo.shop.food.dto.WeekScheduleInput;
import ai.neargo.shop.food.dto.OptionGroupConfigVO;
import ai.neargo.shop.food.dto.PublicationSnapshot;
import ai.neargo.shop.food.entity.FnbConfigRevision;
import ai.neargo.shop.food.mapper.FoodMappers.MenuChannelBindingMapper;
import ai.neargo.shop.food.policy.MenuPublicationDocuments;
import ai.neargo.shop.food.policy.PublicationDocuments;
import ai.neargo.shop.food.spi.MenuChannelAuthorityPort;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import ai.neargo.shop.food.dto.RepublishConfigReq;
import ai.neargo.shop.food.dto.PublishConfigReq;
import ai.neargo.shop.food.dto.StationContracts.StationVO;
import ai.neargo.shop.food.mapper.FoodMappers.ActionLogMapper;
import ai.neargo.shop.food.mapper.FoodMappers.ConfigRevisionMapper;
import ai.neargo.shop.food.mapper.FoodMappers.ModifierMapper;
import ai.neargo.shop.food.mapper.FoodMappers.ModifierComponentMapper;
import ai.neargo.shop.food.mapper.FoodMappers.OptionGroupMapper;
import ai.neargo.shop.food.mapper.FoodMappers.OptionGroupItemMapper;
import ai.neargo.shop.food.policy.StationPublicationSource;
import ai.neargo.shop.food.policy.MenuPublicationSource;
import ai.neargo.shop.food.service.impl.ConfigRevisionServiceImpl;
import ai.neargo.shop.idem.IdempotencyService;
import ai.neargo.shop.event.outbox.OutboxPublisher;
import ai.neargo.shop.spi.product.GoodsCatalogQueryPort;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

class CatalogPublicationServiceTest {
    @Test void stationAppendsFrozenRoutesAndReceiptReplaysAfterAuthorityOutage() {
        new ConfigRevisionServiceTest().run((c, f) -> {
            var station = mock(StationPublicationSource.class);
            var snapshot = new StationPublicationSource.Captured(
                    new StationVO("ST", 0, "DRAFT", "A", "吧台", "BAR", true, null),
                    List.of(new StationPublicationSource.Route("SKU", "MAKE", 1)), List.of("SKU"));
            when(station.capture(anyString(), anyString(), anyString(), anyBoolean())).thenReturn(snapshot);
            var goods = mock(GoodsCatalogQueryPort.class);
            when(goods.getSku(anyString(), anyString(), anyString())).thenAnswer(i -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                return new GoodsCatalogQueryPort.SkuIdentityFact("SKU", "G", "E", "A", 0);
            });
            when(goods.getGoods(anyString(), anyString(), anyString())).thenReturn(new GoodsCatalogQueryPort.GoodsFact(
                    "G", "E", "A", "饮料", null, true, "APPROVED", 0,
                    List.of(new GoodsCatalogQueryPort.SkuFact("SKU", "中", 0))));
            when(station.currentVersion(anyString(), anyString(), anyString(), anyBoolean())).thenReturn(1L);
            var service = new ConfigRevisionServiceImpl(c.getBean(ConfigRevisionMapper.class),
                    c.getBean(ModifierMapper.class), c.getBean(ModifierComponentMapper.class),
                    c.getBean(OptionGroupMapper.class), c.getBean(OptionGroupItemMapper.class),
                    c.getBean(ActionLogMapper.class), c.getBean(IdempotencyService.class),
                    c.getBean("foodOutboxPublisher", OutboxPublisher.class), c.getBean(ObjectMapper.class),
                    c.getBean(PlatformTransactionManager.class), goods, (e, ids) -> List.of(),
                    Clock.fixed(Instant.parse("2026-10-21T08:00:00Z"), ZoneOffset.UTC), null, null, null,
                    null, station, () -> null);
            var req = new PublishConfigReq(0L, OffsetDateTime.parse("2026-10-21T08:00:00Z"), null);
            var first = service.publishStation("E", "A", "ST", "actor", req, "station-publish");
            var content = service.getRevision("E", "A", first.publication().revisionNo());
            assertThat(content.definitionSnapshot()).contains("\"routeVersion\":1");
            when(station.capture(anyString(), anyString(), anyString(), anyBoolean())).thenReturn(
                    new StationPublicationSource.Captured(new StationVO("ST", 1, "DRAFT", "A", "新吧台", "BAR",
                            true, first.publication().revisionNo()),
                            List.of(new StationPublicationSource.Route("SKU", "MAKE", 1)), List.of("SKU")));
            var restored = service.republishRevision("E", "A", first.publication().revisionNo(), "actor",
                    new RepublishConfigReq(1L, req.effectiveAt(), "恢复原工位配置", "APR-RESTORE"), "restore");
            assertThat(restored.publication().revision()).isEqualTo(2);
            assertThat(f.sql().queryForObject("SELECT reason FROM fnb_action_log WHERE action='REPUBLISH_CONFIG'",
                    String.class)).contains("APR-RESTORE", "恢复原工位配置");
            assertThat(service.getRevision("E", "A", restored.publication().revisionNo()).definitionSnapshot())
                    .isEqualTo(content.definitionSnapshot());
            assertThat(service.getRevision("E", "A", first.publication().revisionNo())).isEqualTo(content);
            when(goods.getSku(anyString(), anyString(), anyString())).thenThrow(new IllegalStateException("offline"));
            assertThat(service.publishStation("E", "A", "ST", "actor", req, "station-publish")).isEqualTo(first);
            assertThatThrownBy(() -> service.publishStation("E", "A", "ST", "actor", req, "new-command"))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM fnb_config_revision", Integer.class)).isEqualTo(2);
        });
    }
    @Test void historicalMenuCannotRepublishCancelledEmbeddedDependency() {
        menu(true,false,t -> {
            t.sql().update("UPDATE fnb_config_revision SET status='CANCELLED' WHERE revision_no='DEP'");
            denied(t,"cancelled");
        });
    }
    @Test void historicalMenuCannotRepublishChangedEmbeddedDependencyHash() {
        menu(true,false,t -> {
            t.sql().update("UPDATE fnb_config_revision SET content_hash=? WHERE revision_no='DEP'","0".repeat(64));
            denied(t,"changed-hash");
        });
    }
    @Test void historicalMenuCannotRepublishDependencyOutsideCurrentStore() {
        menu(true,false,t -> {
            t.sql().update("UPDATE fnb_config_revision SET scope_store_nos='[\"B\"]' WHERE revision_no='DEP'");
            denied(t,"wrong-store");
        });
    }
    @Test void historicalMenuCannotRepublishDisabledOrReboundLocalChannelDespiteUpstreamApproval() {
        menu(false,true,t -> {
            t.sql().update("UPDATE fnb_menu_channel_binding SET status='DISABLED' WHERE binding_no='BIND'");
            denied(t,"disabled");
            t.sql().update("UPDATE fnb_menu_channel_binding SET status='ACTIVE',external_store_ref='OTHER' "
                    +"WHERE binding_no='BIND'");
            denied(t,"rebound");
        });
    }
    @Test void historicalMenuRechecksLocalChannelAfterExternalAuthorityRead() {
        menu(false,true,t -> { t.disableDuringAuthority().set(true); denied(t,"raced-disable"); });
    }
    @Test void historicalMenuAcceptsCurrentEligibleChannelAndAppendsUnchangedFrozenDefinition() {
        menu(false,true,t -> {
            String bytes=t.sql().queryForObject("SELECT definition_snapshot FROM fnb_config_revision "
                    +"WHERE revision_no='SOURCE'",String.class);
            var result=t.service().republishRevision("E","A","SOURCE","actor",restore(),"eligible");
            assertThat(result.publication().revision()).isEqualTo(2);
            assertThat(t.service().getRevision("E","A",result.publication().revisionNo()).definitionSnapshot())
                    .isEqualTo(bytes);
        });
    }
    @Test void publicationAndHistoricalRestoreRejectDistinctBindingsForTheSameChannelIdentity() {
        menu(false,true,t -> {
            overlappingChannels(t,"ACC");
            var counts=List.of("fnb_config_revision","fnb_action_log","sys_idempotent","sys_outbox").stream()
                    .map(table -> t.sql().queryForObject("SELECT COUNT(*) FROM "+table,Long.class)).toList();
            assertThatThrownBy(() -> t.service().publishMenu("E","A","MENU","actor",
                    new PublishConfigReq(1L,ConfigRevisionServiceTest.NOW,null),"same-channel-publish"))
                    .isInstanceOfSatisfying(BizException.class,
                            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.CONFLICT));
            assertThat(List.of("fnb_config_revision","fnb_action_log","sys_idempotent","sys_outbox").stream()
                    .map(table -> t.sql().queryForObject("SELECT COUNT(*) FROM "+table,Long.class)).toList())
                    .isEqualTo(counts);
            denied(t,"same-channel-restore");
        });
    }
    @Test void historicalRestoreRejectsDistinctBindingsForTheSameChannelIdentity() {
        menu(false,true,t -> { overlappingChannels(t,"ACC"); denied(t,"same-channel-only-restore"); });
    }
    @Test void genuinelyDifferentChannelAccountsCanPublishAndRestoreOverlappingExclusiveMenus() {
        menu(false,true,t -> {
            overlappingChannels(t,"DIFFERENT_ACCOUNT");
            assertThat(t.service().publishMenu("E","A","MENU","actor",
                    new PublishConfigReq(1L,ConfigRevisionServiceTest.NOW,null),"different-channel-publish"))
                    .isNotNull();
            assertThat(t.service().republishRevision("E","A","SOURCE","actor",restore(),
                    "different-channel-restore")).isNotNull();
        });
    }
    private static void overlappingChannels(HistoricalMenu t,String otherAccount) {
        var original=t.json().readValue(t.sql().queryForObject("SELECT definition_snapshot FROM fnb_config_revision "
                +"WHERE revision_no='SOURCE'",String.class),MenuPublicationSnapshot.class);
        var current=channelSnapshot(original,"MENU","BIND","ACC",0);
        String document=MenuPublicationDocuments.freeze(t.json(),current);
        t.sql().update("UPDATE fnb_config_revision SET definition_snapshot=?,content_hash=? WHERE revision_no='SOURCE'",
                document,PublicationDocuments.hash(t.json(),document));
        var other=channelSnapshot(original,"OTHER_MENU","OTHER_BIND",otherAccount,0);
        t.revisions().append(revision(t.json(),"OTHER_REV","MENU","OTHER_MENU",4,
                MenuPublicationDocuments.freeze(t.json(),other)));
        t.sql().update("INSERT INTO fnb_menu(entity_no,menu_no,status) VALUES ('E','OTHER_MENU','PUBLISHED')");
        t.sql().update("INSERT INTO fnb_menu_store(entity_no,menu_no,store_no) VALUES ('E','OTHER_MENU','A')");
        var working=channelSnapshot(original,"MENU","BIND","ACC",1).menu();
        when(t.menus().capture(anyString(),anyString(),anyString(),anyBoolean(),any(),any(),any()))
                .thenReturn(working);
        when(t.menus().skuNos(any())).thenReturn(List.of("SKU"));
        when(t.menus().goodsNos(any())).thenReturn(List.of("G"));
    }
    private static MenuPublicationSnapshot channelSnapshot(MenuPublicationSnapshot original,String number,
            String binding,String account,long version) {
        var previous=original.menu();
        var definition=new MenuConfigResultVO(new MenuVO(number,"A","Channel meal","TIME","UTC",0,"MEAL",
                "DRAFT",version,version),new MenuScheduleVO(null,null,
                        List.of(new WeekScheduleInput(3,"00:00","00:00",1)),List.of()),
                previous.definition().composition(),
                List.of(new MenuContextInput("CHANNEL","DELIVERY",binding,"PRICE")));
        var captured=new MenuPublicationSnapshot.Captured("E","A",definition,previous.dishes(),
                previous.itemOptions(),previous.dependencies(),List.of(new MenuChannelBindingVO(binding,number,"A",
                        "DELIVEROO",account,"EXT","ACTIVE",0L)));
        return new MenuPublicationSnapshot(4,"MENU",number,version,captured,
                original.skuFacts(),original.goodsFacts(),null);
    }
    record HistoricalMenu(ConfigRevisionServiceImpl service,JdbcTemplate sql,AtomicBoolean disableDuringAuthority,
            ObjectMapper json,ConfigRevisionMapper revisions,MenuPublicationSource menus) { }
    private static RepublishConfigReq restore() {
        return new RepublishConfigReq(1L,ConfigRevisionServiceTest.NOW,"Restore historical menu",null);
    }
    private static void denied(HistoricalMenu t,String key) {
        var counts=List.of("fnb_config_revision","fnb_action_log","sys_idempotent","sys_outbox").stream()
                .map(table -> t.sql().queryForObject("SELECT COUNT(*) FROM "+table,Long.class)).toList();
        assertThatThrownBy(() -> t.service().republishRevision("E","A","SOURCE","actor",restore(),key))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.CONFLICT));
        assertThat(List.of("fnb_config_revision","fnb_action_log","sys_idempotent","sys_outbox").stream()
                .map(table -> t.sql().queryForObject("SELECT COUNT(*) FROM "+table,Long.class)).toList())
                .isEqualTo(counts);
    }
    private void menu(boolean dependency,boolean channel,Consumer<HistoricalMenu> test) {
        new ConfigRevisionServiceTest().run((c,f) -> {
            var json=c.getBean(ObjectMapper.class); var revisions=c.getBean(ConfigRevisionMapper.class);
            f.sql().execute("CREATE TABLE fnb_menu (id BIGINT AUTO_INCREMENT PRIMARY KEY,entity_no VARCHAR(32),"
                    +"menu_no VARCHAR(32),status VARCHAR(16),deleted INT DEFAULT 0)");
            f.sql().execute("CREATE TABLE fnb_menu_store (id BIGINT AUTO_INCREMENT PRIMARY KEY,entity_no VARCHAR(32),"
                    +"menu_no VARCHAR(32),store_no VARCHAR(32),deleted INT DEFAULT 0)");
            f.sql().execute("CREATE TABLE fnb_menu_channel_binding (id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                    +"tenant_no VARCHAR(32) DEFAULT 'MAIN',entity_no VARCHAR(32),created_at DATETIME(3),"
                    +"created_by VARCHAR(32),updated_at DATETIME(3),updated_by VARCHAR(32),version BIGINT DEFAULT 0,"
                    +"deleted INT DEFAULT 0,binding_no VARCHAR(32),menu_no VARCHAR(32),store_no VARCHAR(32),"
                    +"provider VARCHAR(32),channel_account_ref VARCHAR(64),external_store_ref VARCHAR(64),"
                    +"status VARCHAR(16))");
            f.sql().update("INSERT INTO fnb_menu(entity_no,menu_no,status) VALUES ('E','MENU','PUBLISHED')");
            f.sql().update("INSERT INTO fnb_menu_store(entity_no,menu_no,store_no) VALUES ('E','MENU','A')");
            var template=c.getBean(SqlSessionTemplate.class);
            if(!template.getConfiguration().hasMapper(MenuChannelBindingMapper.class))
                template.getConfiguration().addMapper(MenuChannelBindingMapper.class);
            var deps=new ArrayList<MenuPublicationSnapshot.Dependency>();
            if(dependency) {
                var group=new PublicationSnapshot(1,"OPTION_GROUP","GROUP",0,null,
                        new OptionGroupConfigVO("GROUP","OPTION_GROUP",0,"DRAFT",new OptionGroupConfigVO.Data(
                                "Group",BigDecimal.ZERO,BigDecimal.ONE,null,Map.of(),List.of())),
                        List.of(),List.of(),List.of(),null);
                String bytes=PublicationDocuments.freeze(json,group);
                var row=revision(json,"DEP","OPTION_GROUP","GROUP",1,bytes); revisions.append(row);
                deps.add(new MenuPublicationSnapshot.Dependency("DEP","OPTION_GROUP","GROUP",1,1,
                        row.getContentHash(),bytes));
            }
            var groups=dependency?List.of("GROUP"):List.<String>of();
            var item=new MenuItemInput("ITEM","G",0,true,"EXPLICIT",List.of("SKU"),"REPLACE",groups,List.of());
            var definition=new MenuConfigResultVO(new MenuVO("MENU","A","Menu","TIME","UTC",0,null,"DRAFT",0L,0L),
                    new MenuScheduleVO(null,null,List.of(),List.of()),new MenuCompositionVO(List.of(
                            new MenuCategoryInput("CAT","Category",0,List.of(item)))),List.of(channel
                    ?new MenuContextInput("CHANNEL","DELIVERY","BIND","PRICE")
                    :new MenuContextInput("POS","DINE_IN",null,null)));
            var bindings=channel?List.of(new MenuChannelBindingVO("BIND","MENU","A","DELIVEROO","ACC","EXT",
                    "ACTIVE",0L)):List.<MenuChannelBindingVO>of();
            if(channel) f.sql().update("INSERT INTO fnb_menu_channel_binding(entity_no,binding_no,menu_no,store_no,"
                    +"provider,channel_account_ref,external_store_ref,status) "
                    +"VALUES ('E','BIND','MENU','A','DELIVEROO','ACC','EXT','ACTIVE')");
            var captured=new MenuPublicationSnapshot.Captured("E","A",definition,List.of(new DishVO("G","SINGLE",0,
                    new DishPresentation(null,List.of(),List.of(),List.of(),List.of()),
                    new MeasurementPolicy("EA","COUNT",0,null),0,0)),
                    List.of(new MenuPublicationSnapshot.ItemOptions("ITEM",groups)),deps,bindings);
            var sku=new GoodsCatalogQueryPort.SkuIdentityFact("SKU","G","E","A",0);
            var product=new GoodsCatalogQueryPort.GoodsFact("G","E","A","Goods",null,true,"APPROVED",0,
                    List.of(new GoodsCatalogQueryPort.SkuFact("SKU","Size",0)));
            var snapshot=new MenuPublicationSnapshot(4,"MENU","MENU",0,captured,List.of(sku),List.of(product),null);
            String bytes=MenuPublicationDocuments.freeze(json,snapshot);
            MenuPublicationDocuments.validate(json,"MENU",bytes);
            revisions.append(revision(json,"SOURCE","MENU","MENU",4,bytes));
            var menus=mock(MenuPublicationSource.class);
            when(menus.currentVersion(anyString(),anyString(),anyString(),anyBoolean())).thenReturn(1L);
            var goods=mock(GoodsCatalogQueryPort.class);
            when(goods.getSku(anyString(),anyString(),anyString())).thenAnswer(i -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse(); return sku;
            });
            when(goods.getGoods(anyString(),anyString(),anyString())).thenAnswer(i -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse(); return product;
            });
            var disable=new AtomicBoolean();
            MenuChannelAuthorityPort authority=new MenuChannelAuthorityPort() {
                @Override public boolean authorized(String e,String s,String p,String a,String x) {
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                    if(disable.getAndSet(false))
                        f.sql().update("UPDATE fnb_menu_channel_binding SET status='DISABLED'");
                    return true;
                }
                @Override public boolean pricePolicyAuthorized(String e,String s,String b,String p) {
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse(); return true;
                }
            };
            var service=new ConfigRevisionServiceImpl(revisions,c.getBean(ModifierMapper.class),
                    c.getBean(ModifierComponentMapper.class),c.getBean(OptionGroupMapper.class),
                    c.getBean(OptionGroupItemMapper.class),c.getBean(ActionLogMapper.class),
                    c.getBean(IdempotencyService.class),c.getBean("foodOutboxPublisher",OutboxPublisher.class),json,
                    c.getBean(PlatformTransactionManager.class),goods,(e,ids) -> List.of(),
                    Clock.fixed(ConfigRevisionServiceTest.NOW.toInstant(),ZoneOffset.UTC),null,null,null,menus,null,
                    () -> authority,template.getMapper(MenuChannelBindingMapper.class));
            test.accept(new HistoricalMenu(service,f.sql(),disable,json,revisions,menus));
        });
    }
    private static FnbConfigRevision revision(ObjectMapper json,String rev,String kind,String number,int schema,
            String document) {
        var row=new FnbConfigRevision(); row.setEntityNo("E"); row.setCreatedBy("actor"); row.setRevisionNo(rev);
        row.setConfigKind(kind); row.setConfigNo(number); row.setRevision(1L); row.setContentSchema(schema);
        row.setDefinitionSnapshot(document); row.setContentHash(PublicationDocuments.hash(json,document));
        row.setEffectiveAt(ConfigRevisionServiceTest.NOW.toLocalDateTime()); row.setScopeStoreNos("[\"A\"]");
        row.setStatus("EFFECTIVE"); return row;
    }

}
