package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.event.outbox.OutboxPublisher;
import ai.neargo.shop.food.dto.CreateMenuChannelBindingReq;
import ai.neargo.shop.event.outbox.runtime.UnifiedOutboxFacade;
import org.mybatis.spring.SqlSessionTemplate;
import java.util.List;
import java.util.Set;
import ai.neargo.shop.food.dto.UpdateMenuChannelBindingReq;
import ai.neargo.shop.food.mapper.FoodMappers.ActionLogMapper;
import ai.neargo.shop.food.mapper.FoodMappers.MenuMapper;
import ai.neargo.shop.food.mapper.FoodMappers.MenuStoreMapper;
import ai.neargo.shop.food.mapper.FoodMappers.MenuChannelBindingMapper;
import ai.neargo.shop.food.service.impl.MenuChannelBindingServiceImpl;
import ai.neargo.shop.food.spi.MenuChannelAuthorityPort;
import ai.neargo.shop.idem.IdempotencyService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

/** Real binding and owner rows verify local draft invalidation and external activation authority. */
class MenuChannelBindingServiceTest extends FoodWriteGuardFixture {
    record State(Fixture fixture,MenuChannelBindingServiceImpl service,
            AtomicBoolean authorized,AtomicBoolean fail,AtomicBoolean outage) { }
    private void run(Consumer<State> test) {
        runner().withUserConfiguration(StorePolicyPublicationTest.Publication.class).run(c -> {
            var f=prepare(c); StorePolicyPublicationTest.createPolicyTables(f);
            String common="id BIGINT AUTO_INCREMENT PRIMARY KEY,tenant_no VARCHAR(32) DEFAULT 'MAIN',"
                    +"entity_no VARCHAR(32),created_at DATETIME(3),created_by VARCHAR(32),updated_at DATETIME(3),"
                    +"updated_by VARCHAR(32),version BIGINT DEFAULT 0,deleted INT DEFAULT 0,";
            f.sql().execute("CREATE TABLE fnb_menu ("+common+"menu_no VARCHAR(32) UNIQUE,name VARCHAR(64),"
                    +"menu_type VARCHAR(16),timezone VARCHAR(64),valid_from DATE,valid_to DATE,priority INT,"
                    +"exclusive_group VARCHAR(32),status VARCHAR(16),config_version BIGINT,"
                    +"published_revision_no VARCHAR(32))");
            f.sql().execute("CREATE TABLE fnb_menu_store ("+common+"menu_no VARCHAR(32),store_no VARCHAR(32),"
                    +"override_revision_no VARCHAR(32))");
            f.sql().execute("CREATE TABLE fnb_menu_channel_binding ("+common+"binding_no VARCHAR(32) UNIQUE,"
                    +"menu_no VARCHAR(32),store_no VARCHAR(32),provider VARCHAR(32),channel_account_ref VARCHAR(64),"
                    +"external_store_ref VARCHAR(64),status VARCHAR(16))");
            f.sql().update("INSERT INTO fnb_menu(entity_no,menu_no,name,status,config_version) "
                    +"VALUES ('E','MENU','Menu','PUBLISHED',0)");
            f.sql().update("INSERT INTO fnb_menu_store(entity_no,menu_no,store_no) VALUES ('E','MENU','A')");
            var template=c.getBean(SqlSessionTemplate.class);
            for(var type:List.of(MenuMapper.class,MenuStoreMapper.class,MenuChannelBindingMapper.class))
                if(!template.getConfiguration().hasMapper(type)) template.getConfiguration().addMapper(type);
            c.getBean(UnifiedOutboxFacade.class).setArchiveOnly(Set.of("PLATFORM:FOOD_MENU_CHANNEL_BINDING_CHANGED"));
            var allow=new AtomicBoolean(); var fail=new AtomicBoolean(); var outage=new AtomicBoolean();
            MenuChannelAuthorityPort authority=new MenuChannelAuthorityPort() {
                @Override public boolean authorized(String e,String s,String p,String a,String x) {
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                    if(outage.get()) throw new IllegalStateException("channel unavailable");
                    return allow.get() && "E".equals(e) && "A".equals(s) && "DELIVEROO".equals(p)
                            && "ACCOUNT".equals(a) && "EXTERNAL".equals(x);
                }
                @Override public boolean pricePolicyAuthorized(String e,String s,String b,String p) { return false; }
            };
            var outbox=c.getBean("foodOutboxPublisher",OutboxPublisher.class);
            var service=new MenuChannelBindingServiceImpl(template.getMapper(MenuChannelBindingMapper.class),
                    template.getMapper(MenuMapper.class),template.getMapper(MenuStoreMapper.class),
                    c.getBean(ActionLogMapper.class),c.getBean(IdempotencyService.class),(event,options) -> {
                        var receipt=outbox.publish(event,options);
                        if(fail.get()) throw new IllegalStateException("after-binding-outbox"); return receipt;
                    },c.getBean(ObjectMapper.class),c.getBean(PlatformTransactionManager.class),() -> authority);
            test.accept(new State(f,service,allow,fail,outage));
        });
    }
    @Test void createsUnverifiedDraftAndActivationNeedsRealAuthorityOutsideTransaction() {
        run(t -> {
            var f=t.fixture();
            var s=t.service(); var req=new CreateMenuChannelBindingReq("MENU","DELIVEROO","ACCOUNT","EXTERNAL");
            var draft=s.createBinding("E","A","actor",req,"create");
            assertThat(draft.status()).isEqualTo("DRAFT");
            assertThat(s.createBinding("E","A","actor",req,"create")).isEqualTo(draft);
            code(() -> s.updateBinding("E","A",draft.bindingNo(),"actor",
                    new UpdateMenuChannelBindingReq(0L,"ACTIVE","EXTERNAL"),"deny"),ErrorCode.CONFLICT);
            assertThat(s.getBinding("E","A",draft.bindingNo()).version()).isZero();
            t.authorized().set(true);
            var active=s.updateBinding("E","A",draft.bindingNo(),"actor",
                    new UpdateMenuChannelBindingReq(0L,"ACTIVE","EXTERNAL"),"activate");
            assertThat(active.version()).isEqualTo(1); assertThat(active.status()).isEqualTo("ACTIVE");
            assertThat(f.sql().queryForObject("SELECT version FROM fnb_menu",Long.class)).isEqualTo(2);
            t.outage().set(true);
            assertThat(s.updateBinding("E","A",draft.bindingNo(),"actor",
                    new UpdateMenuChannelBindingReq(0L,"ACTIVE","EXTERNAL"),"activate")).isEqualTo(active);
            code(() -> s.getBinding("E","B",draft.bindingNo()),ErrorCode.NOT_FOUND);
            assertThat(s.listBindings("E","A",1,20,"DELIVEROO","MENU").total()).isEqualTo(1);
            assertThat(s.listBindings("E","B",1,20,null,null).total()).isZero();
        });
    }
    @Test void disabledOwnerStaleCasAndImmutableBindingIdentityCannotBeBypassed() {
        run(t -> {
            var f=t.fixture();
            var s=t.service();
            var draft=s.createBinding("E","A","actor",
                    new CreateMenuChannelBindingReq("MENU","KEETA","PLANNED","PLANNED"),"draft");
            code(() -> s.updateBinding("E","A",draft.bindingNo(),"actor",
                    new UpdateMenuChannelBindingReq(0L,"ACTIVE","PLANNED"),"keeta"),ErrorCode.CONFLICT);
            s.updateBinding("E","A",draft.bindingNo(),"actor",
                    new UpdateMenuChannelBindingReq(0L,"DISABLED","PLANNED"),"disable");
            code(() -> s.updateBinding("E","A",draft.bindingNo(),"actor",
                    new UpdateMenuChannelBindingReq(0L,"DISABLED","PLANNED"),"stale"),ErrorCode.CONFLICT);
            f.sql().update("UPDATE fnb_menu SET status='DISABLED' WHERE menu_no=?","MENU");
            code(() -> s.createBinding("E","A","actor",
                    new CreateMenuChannelBindingReq("MENU","DELIVEROO","A","X"),"owner-disabled"),
                    ErrorCode.CONFLICT);
            code(() -> s.createBinding("E","A","other",
                    new CreateMenuChannelBindingReq("MENU","KEETA","PLANNED","PLANNED"),"draft"),
                    ErrorCode.NOT_FOUND);
        });
    }
    @Test void sharedMenuCannotBeInvalidatedBySingleStoreBindingWrites() {
        run(t -> {
            var s=t.service(); var f=t.fixture();
            var draft=s.createBinding("E","A","actor",
                    new CreateMenuChannelBindingReq("MENU","KEETA","PLANNED","PLANNED"),"draft");
            f.sql().update("INSERT INTO fnb_menu_store(entity_no,menu_no,store_no) VALUES ('E','MENU','B')");
            rejectWithoutWrites(t,() -> s.createBinding("E","A","actor",
                    new CreateMenuChannelBindingReq("MENU","DELIVEROO","PLAN","PLAN"),"shared-create"));
            rejectWithoutWrites(t,() -> s.updateBinding("E","A",draft.bindingNo(),"actor",
                    new UpdateMenuChannelBindingReq(0L,"DISABLED","PLANNED"),"shared-update"));
        });
    }
    @Test void overriddenStoreMenuCannotBeInvalidatedByBindingWrites() {
        run(t -> {
            var s=t.service(); var f=t.fixture();
            var draft=s.createBinding("E","A","actor",
                    new CreateMenuChannelBindingReq("MENU","KEETA","PLANNED","PLANNED"),"draft");
            f.sql().update("UPDATE fnb_menu_store SET override_revision_no='OVERRIDE' WHERE menu_no='MENU'");
            rejectWithoutWrites(t,() -> s.createBinding("E","A","actor",
                    new CreateMenuChannelBindingReq("MENU","DELIVEROO","PLAN","PLAN"),"override-create"));
            rejectWithoutWrites(t,() -> s.updateBinding("E","A",draft.bindingNo(),"actor",
                    new UpdateMenuChannelBindingReq(0L,"DISABLED","PLANNED"),"override-update"));
        });
    }
    @Test void negativeOwnerConfigVersionCannotAdvanceThroughBindingWrites() {
        run(t -> {
            var s=t.service(); var f=t.fixture();
            var draft=s.createBinding("E","A","actor",
                    new CreateMenuChannelBindingReq("MENU","KEETA","PLANNED","PLANNED"),"draft");
            f.sql().update("UPDATE fnb_menu SET config_version=-1 WHERE menu_no='MENU'");
            rejectWithoutWrites(t,() -> s.createBinding("E","A","actor",
                    new CreateMenuChannelBindingReq("MENU","DELIVEROO","PLAN","PLAN"),"negative-create"));
            rejectWithoutWrites(t,() -> s.updateBinding("E","A",draft.bindingNo(),"actor",
                    new UpdateMenuChannelBindingReq(0L,"DISABLED","PLANNED"),"negative-update"));
        });
    }
    private static void rejectWithoutWrites(State state,Runnable action) {
        var sql=state.fixture().sql();
        var head=sql.queryForMap("SELECT version,config_version,status FROM fnb_menu WHERE menu_no='MENU'");
        var bindings=sql.queryForList("SELECT binding_no,version,status FROM fnb_menu_channel_binding "
                + "ORDER BY binding_no");
        var tables=List.of("sys_idempotent","sys_outbox","fnb_action_log");
        var counts=tables.stream().map(table -> sql.queryForObject("SELECT COUNT(*) FROM "+table,Long.class)).toList();
        code(action,ErrorCode.CONFLICT);
        assertThat(sql.queryForMap("SELECT version,config_version,status FROM fnb_menu WHERE menu_no='MENU'"))
                .isEqualTo(head);
        assertThat(sql.queryForList("SELECT binding_no,version,status FROM fnb_menu_channel_binding "
                + "ORDER BY binding_no"))
                .isEqualTo(bindings);
        assertThat(tables.stream().map(table -> sql.queryForObject("SELECT COUNT(*) FROM "+table,Long.class)).toList())
                .isEqualTo(counts);
    }
    static void code(Runnable action,ErrorCode expected) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BizException.class,
                e -> assertThat(e.errorCode()).isEqualTo(expected));
    }
}
