package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.event.outbox.OutboxPublisher;
import ai.neargo.shop.event.outbox.runtime.UnifiedOutboxFacade;
import ai.neargo.shop.food.dto.StationContracts.CreateStationReq;
import ai.neargo.shop.food.dto.StationContracts.UpdateStationReq;
import ai.neargo.shop.food.dto.StationContracts.ReplaceStationRoutesReq;
import ai.neargo.shop.food.dto.StationContracts.StationRouteInput;
import ai.neargo.shop.food.mapper.FoodMappers.ActionLogMapper;
import ai.neargo.shop.food.mapper.FoodMappers.StationMapper;
import ai.neargo.shop.food.mapper.FoodMappers.SkuStationMapper;
import ai.neargo.shop.food.service.StationService;
import ai.neargo.shop.food.policy.StationPublicationSource;
import ai.neargo.shop.food.service.impl.StationServiceImpl;
import ai.neargo.shop.idem.IdempotencyService;
import ai.neargo.shop.spi.product.GoodsCatalogQueryPort;
import java.util.List;
import java.util.Set;
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

/** Primary-schema tests cover station ownership and immutable route counters rather than mapper stubs. */
class StationServiceTest extends FoodWriteGuardFixture {
    private void run(BiConsumer<AssertableApplicationContext, Fixture> test) {
        runner().withUserConfiguration(StorePolicyPublicationTest.Publication.class,
                OptionGroupServiceTest.Groups.class, Stations.class).run(c -> {
                    var f = new OptionGroupServiceTest().fixture(c);
                    for (String store : List.of("A","B")) f.sql().update(
                            "INSERT INTO fnb_store_policy(entity_no,store_no,policy_no) VALUES (?,?,?)",
                            "E",store,"POL-"+store);
                    String common = "id BIGINT AUTO_INCREMENT PRIMARY KEY,tenant_no VARCHAR(32) DEFAULT 'MAIN',"
                            + "entity_no VARCHAR(32),created_at DATETIME(3),created_by VARCHAR(32),"
                            + "updated_at DATETIME(3),updated_by VARCHAR(32),version BIGINT DEFAULT 0,"
                            + "deleted INT DEFAULT 0,";
                    f.sql().execute("CREATE TABLE fnb_station (" + common + "station_no VARCHAR(32) UNIQUE,"
                            + "store_no VARCHAR(32),name VARCHAR(64),station_type VARCHAR(24),enabled BOOLEAN,"
                            + "published_revision_no VARCHAR(32))");
                    f.sql().execute("CREATE TABLE fnb_sku_station (" + common + "sku_no VARCHAR(32),"
                            + "station_no VARCHAR(32),task_role VARCHAR(16),route_version BIGINT,store_no VARCHAR(32),"
                            + "UNIQUE(store_no,sku_no,station_no,task_role))");
                    c.getBean(UnifiedOutboxFacade.class).setArchiveOnly(Set.of("PLATFORM:FOOD_STATION_CHANGED"));
                    test.accept(c, f);
                });
    }
    @Test void createUpdateReadListScopeCasAndActorBoundReplay() {
        run((c,f) -> {
            var s = c.getBean(StationService.class);
            var a = s.createStation("E","A","actor",new CreateStationReq(" 热厨 ","HOT"),"create");
            assertThat(a.version()).isZero();
            assertThat(s.createStation("E","A","actor",new CreateStationReq(" 热厨 ","HOT"),"create"))
                    .isEqualTo(a);
            assertThat(s.getStation("E","A",a.configNo()).config().name()).isEqualTo("热厨");
            code(() -> s.getStation("E","B",a.configNo()), ErrorCode.NOT_FOUND);
            code(() -> s.createStation("E","A","other",new CreateStationReq("热厨","HOT"),"create"),
                    ErrorCode.NOT_FOUND);
            var changed = s.updateStation("E","A",a.configNo(),"actor",
                    new UpdateStationReq(0L,"冷厨","COLD",false),"update");
            assertThat(changed.version()).isEqualTo(1);
            code(() -> s.updateStation("E","A",a.configNo(),"actor",
                    new UpdateStationReq(0L,"x","HOT",true),"stale"), ErrorCode.CONFLICT);
            s.createStation("E","B","actor",new CreateStationReq("外店","BAR"),"other-store");
            assertThat(s.listStations("E","A",1,20).total()).isEqualTo(1);
            assertThat(s.listStations("E","A",1,20).records().getFirst().enabled()).isFalse();
            assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM fnb_action_log",Integer.class)).isEqualTo(3);
        });
    }
    @Test void routesRejectDisabledForeignAndDuplicateRoleAndPreserveVersionAfterClear() {
        run((c,f) -> {
            var s=c.getBean(StationService.class);
            String a=s.createStation("E","A","actor",new CreateStationReq("热厨","HOT"),"a").configNo();
            String b=s.createStation("E","B","actor",new CreateStationReq("酒吧","BAR"),"b").configNo();
            code(() -> s.replaceRoutes("E","A","SKU","actor",routes(0,b),"foreign"),ErrorCode.BAD_REQUEST);
            var result=s.replaceRoutes("E","A","SKU","actor",routes(0,a),"route");
            assertThat(result.version()).isEqualTo(1);
            assertThat(s.replaceRoutes("E","A","SKU","actor",routes(0,a),"route")).isEqualTo(result);
            code(() -> s.replaceRoutes("E","A","SKU","actor",routes(0,a),"stale"),ErrorCode.CONFLICT);
            code(() -> s.replaceRoutes("E","A","SKU","actor",new ReplaceStationRoutesReq(1L,List.of(
                    new StationRouteInput(a,"MAKE"),new StationRouteInput(a,"MAKE"))),"duplicate"),
                    ErrorCode.BAD_REQUEST);
            s.replaceRoutes("E","A","SKU","actor",new ReplaceStationRoutesReq(1L,List.of()),"clear");
            assertThat(s.getRoutes("E","A","SKU").version()).isEqualTo(2);
            assertThat(s.getRoutes("E","A","SKU").routes()).isEmpty();
            s.replaceRoutes("E","A","SKU","actor",routes(2,a),"readd");
            assertThat(s.getRoutes("E","A","SKU").version()).isEqualTo(3);
            s.updateStation("E","A",a,"actor",new UpdateStationReq(0L,"热厨","HOT",false),"disable");
            code(() -> s.replaceRoutes("E","A","SKU","actor",routes(3,a),"disabled"),ErrorCode.BAD_REQUEST);
        });
    }
    @Test void authorityReadsOutsideTransactionAndOutboxFailureRollsBackRoutesAndReceipt() {
        run((c,f) -> {
            var s=c.getBean(StationService.class); var state=c.getBean(State.class);
            String a=s.createStation("E","A","actor",new CreateStationReq("热厨","HOT"),"a").configNo();
            s.replaceRoutes("E","A","SKU","actor",routes(0,a),"route");
            state.fail.set(true);
            assertThatThrownBy(() -> s.replaceRoutes("E","A","SKU","actor",
                    new ReplaceStationRoutesReq(1L,List.of()),"fail")).isInstanceOf(IllegalStateException.class);
            assertThat(s.getRoutes("E","A","SKU").version()).isEqualTo(1);
            assertThat(s.getRoutes("E","A","SKU").routes()).hasSize(1);
            assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM sys_idempotent",Integer.class)).isEqualTo(2);
            state.outage.set(true);
            assertThat(s.replaceRoutes("E","A","SKU","actor",routes(0,a),"route").version()).isEqualTo(1);
            assertThatThrownBy(() -> s.replaceRoutes("E","A","SKU","actor",routes(1,a),"new"))
                    .isInstanceOf(IllegalStateException.class);
        });
    }
    @Test void initialEmptyRouteIsNoOpAndMultistationRolesFreezeCapturedVersion() {
        run((c,f) -> {
            var s=c.getBean(StationService.class);
            assertThat(s.replaceRoutes("E","A","SKU","actor",
                    new ReplaceStationRoutesReq(0L,List.of()),"empty").version()).isZero();
            String a=s.createStation("E","A","actor",new CreateStationReq("热厨","HOT"),"a").configNo();
            String b=s.createStation("E","A","actor",new CreateStationReq("打包","PACK"),"b").configNo();
            s.replaceRoutes("E","A","SKU","actor",new ReplaceStationRoutesReq(0L,List.of(
                    new StationRouteInput(a,"MAKE"),new StationRouteInput(b,"PACK"),
                    new StationRouteInput(a,"ASSEMBLE"))),"multi");
            assertThat(s.getRoutes("E","A","SKU").routes()).extracting(StationRouteInput::taskRole)
                    .containsExactly("ASSEMBLE","MAKE","PACK");
            var source=new StationPublicationSource(
                    c.getBean(StationMapper.class),c.getBean(SkuStationMapper.class),() -> true);
            var captured=f.tx().execute(tx -> source.capture("E","A",a,true));
            assertThat(captured.routes()).hasSize(2);
            assertThat(captured.routes()).allMatch(r -> r.routeVersion()==1);
            s.replaceRoutes("E","A","SKU","actor",new ReplaceStationRoutesReq(1L,List.of()),"clear");
            assertThat(captured.routes()).hasSize(2);
            assertThat(source.capture("E","A",a,false).routes()).isEmpty();
        });
    }
    static ReplaceStationRoutesReq routes(long version,String station) {
        return new ReplaceStationRoutesReq(version,List.of(new StationRouteInput(station,"MAKE")));
    }
    static void code(Runnable call,ErrorCode code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(BizException.class,
                e -> assertThat(e.errorCode()).isEqualTo(code));
    }
    static class State { final AtomicBoolean fail=new AtomicBoolean(); final AtomicBoolean outage=new AtomicBoolean(); }
    @Configuration(proxyBeanMethods=false)
    static class Stations {
        @Bean State stationState() { return new State(); }
        @Bean MapperFactoryBean<StationMapper> stationHeads(@Qualifier("foodSqlSessionTemplate") SqlSessionTemplate t) {
            var b=new MapperFactoryBean<>(StationMapper.class); b.setSqlSessionTemplate(t); return b;
        }
        @Bean MapperFactoryBean<SkuStationMapper> stationRoutes(
                @Qualifier("foodSqlSessionTemplate") SqlSessionTemplate t) {
            var b=new MapperFactoryBean<>(SkuStationMapper.class); b.setSqlSessionTemplate(t); return b;
        }
        @Bean StationService stationService(StationMapper heads,SkuStationMapper routes,ActionLogMapper actions,
                IdempotencyService idem,@Qualifier("foodOutboxPublisher") OutboxPublisher outbox,
                ObjectMapper json,PlatformTransactionManager manager,State state) {
            var catalog=mock(GoodsCatalogQueryPort.class);
            when(catalog.getSku(anyString(),anyString(),anyString())).thenAnswer(i -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                if(state.outage.get()) throw new IllegalStateException("authority unavailable");
                return new GoodsCatalogQueryPort.SkuIdentityFact(i.getArgument(2),"G",
                        i.getArgument(0),i.getArgument(1),0);
            });
            OutboxPublisher checked=(event,options) -> {
                var result=outbox.publish(event,options);
                if(state.fail.get()) throw new IllegalStateException("after-station-outbox");
                return result;
            };
            return new StationServiceImpl(heads,routes,actions,idem,checked,json,manager,catalog);
        }
    }
}
