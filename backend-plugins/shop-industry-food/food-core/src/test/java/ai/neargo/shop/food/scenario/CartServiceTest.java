package ai.neargo.shop.food.scenario;

import java.io.IOException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.TimeoutException;

import java.util.regex.Pattern;

import org.springframework.core.io.ClassPathResource;

import java.util.concurrent.TimeUnit;

import java.util.concurrent.CountDownLatch;

import java.util.concurrent.Executors;

import ai.neargo.shop.common.ErrorCode;

import ai.neargo.shop.common.BizException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.event.outbox.OutboxPublisher;
import ai.neargo.shop.event.outbox.runtime.UnifiedOutboxFacade;
import ai.neargo.shop.food.dto.AuthorizeCartSessionReq;
import ai.neargo.shop.food.dto.CartAccess;
import ai.neargo.shop.food.dto.CartResultVO;
import ai.neargo.shop.food.dto.CartVersionReq;
import ai.neargo.shop.food.dto.CatalogItemVO;
import ai.neargo.shop.food.dto.CatalogMenuVO;
import ai.neargo.shop.food.dto.CatalogQuery;
import ai.neargo.shop.food.dto.CatalogSkuVO;
import ai.neargo.shop.food.dto.CatalogVO;
import ai.neargo.shop.food.dto.CreateCartReq;
import ai.neargo.shop.food.dto.CreateCustomerCartReq;
import ai.neargo.shop.food.dto.DishPresentation;
import ai.neargo.shop.food.dto.DishVO;
import ai.neargo.shop.food.dto.MeasurementPolicy;
import ai.neargo.shop.food.dto.OrderLineInput;
import ai.neargo.shop.food.dto.ReplaceCartLineReq;
import ai.neargo.shop.food.entity.FnbCheck;
import ai.neargo.shop.food.entity.FnbCheckCart;
import ai.neargo.shop.food.entity.FnbActionLog;
import ai.neargo.shop.food.entity.FnbCheckCartItem;
import ai.neargo.shop.food.entity.FnbGuestSession;
import ai.neargo.shop.food.entity.FnbSeat;
import ai.neargo.shop.food.entity.FnbTable;
import ai.neargo.shop.food.entity.FnbTableArea;
import ai.neargo.shop.food.entity.FnbTableOccupancy;
import ai.neargo.shop.food.mapper.FoodMappers.ActionLogMapper;
import ai.neargo.shop.food.mapper.FoodMappers.CheckCartItemMapper;
import ai.neargo.shop.food.mapper.FoodMappers.CheckCartMapper;
import ai.neargo.shop.food.mapper.FoodMappers.CheckMapper;
import ai.neargo.shop.food.mapper.FoodMappers.GuestSessionMapper;
import ai.neargo.shop.food.mapper.FoodMappers.SeatMapper;
import ai.neargo.shop.food.mapper.FoodMappers.TableAreaMapper;
import ai.neargo.shop.food.mapper.FoodMappers.TableMapper;
import ai.neargo.shop.food.mapper.FoodMappers.TableOccupancyMapper;
import ai.neargo.shop.food.service.CatalogService;
import ai.neargo.shop.food.service.impl.CheckCartServiceImpl;
import ai.neargo.shop.idem.IdempotencyService;
import ai.neargo.shop.spi.user.StoreDirectoryPort.StoreCard;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

/** Actual owner, cart, audit, idempotency and outbox rows exercise the shared-draft boundary. */
class CartServiceTest extends FoodWriteGuardFixture {
    static final Instant NOW = Instant.parse("2026-10-22T04:00:00Z");
    record State(Fixture f, CheckCartServiceImpl service, AtomicBoolean outage, AtomicBoolean fail) { }
    void run(Consumer<State> test) {
        runner().withUserConfiguration(StorePolicyPublicationTest.Publication.class).run(c -> {
            var f = prepare(c); var template = c.getBean(SqlSessionTemplate.class);
            for (var type : List.of(CheckCartMapper.class, CheckCartItemMapper.class, CheckMapper.class,
                    GuestSessionMapper.class, SeatMapper.class, TableOccupancyMapper.class,
                    TableAreaMapper.class, TableMapper.class)) template.getConfiguration().addMapper(type);
            for (var type : List.of(FnbActionLog.class, FnbCheckCart.class, FnbCheckCartItem.class,
                    FnbCheck.class, FnbGuestSession.class,
                    FnbSeat.class, FnbTableOccupancy.class, FnbTableArea.class, FnbTable.class)) create(f.sql(), type);
            f.sql().execute("CREATE UNIQUE INDEX ux_cart_no ON fnb_check_cart(cart_no)");
            f.sql().execute("CREATE UNIQUE INDEX ux_cart_line ON fnb_check_cart_item(cart_no,source_line_ref)");
            // Original placement guards and historical buyer reads use these durable source/binding facts.
            f.sql().execute("CREATE TABLE fnb_order_batch (tenant_no VARCHAR(32) DEFAULT 'MAIN',"
                    + "entity_no VARCHAR(32),store_no VARCHAR(32),cart_no VARCHAR(32),batch_no VARCHAR(32),"
                    + "buyer_no VARCHAR(32),status VARCHAR(24),deleted INT DEFAULT 0)");
            f.sql().execute("CREATE TABLE fnb_core_order_binding (tenant_no VARCHAR(32) DEFAULT 'MAIN',"
                    + "entity_no VARCHAR(32),batch_no VARCHAR(32),status VARCHAR(24),deleted INT DEFAULT 0)");
            f.sql().update("INSERT INTO fnb_check(entity_no,store_no,check_no,status,version,deleted) "
                    + "VALUES('E','A','CHECK','OPEN',0,0)");
            f.sql().update("INSERT INTO fnb_table_area(entity_no,store_no,area_no,status,version,deleted) "
                    + "VALUES('E','A','AREA','ACTIVE',0,0)");
            f.sql().update("INSERT INTO fnb_table(entity_no,store_no,area_no,table_no,status,version,deleted) "
                    + "VALUES('E','A','AREA','TABLE','ACTIVE',0,0)");
            f.sql().update("INSERT INTO fnb_table_occupancy(entity_no,check_no,table_no,ended_at,version,deleted) "
                    + "VALUES('E','CHECK','TABLE',NULL,0,0)");
            for (String session : List.of("G1", "G2", "G3")) {
                f.sql().update("INSERT INTO fnb_guest_session(entity_no,store_no,table_no,session_no,token_hash,"
                        + "scope,expires_at,version,deleted) VALUES('E','A','TABLE',?,?,?, ?,0,0)", session,
                        digest("secret-"+session), "[\"TABLE_MENU\"]", LocalDateTime.ofInstant(NOW.plusSeconds(3600),
                                ZoneOffset.UTC));
            }
            var outage = new AtomicBoolean(); var fail = new AtomicBoolean();
            CatalogService catalog = new CatalogService() {
                @Override public CatalogVO read(String e, String s, CatalogQuery q) {
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                    if (outage.get()) throw new IllegalStateException("original offline");
                    return catalog(s,q.orderSource(),q.fulfillment());
                }
                @Override public CatalogVO guest(String s,String session,String access,String buyer) {
                    return read("E",s,new CatalogQuery("QR","DINE_IN",null));
                }
                @Override public CatalogVO publicCatalog(String s,String fulfillment) {
                    return read("E",s,new CatalogQuery("H5",fulfillment,null));
                }
            };
            var outbox = c.getBean("foodOutboxPublisher",OutboxPublisher.class);
            c.getBean(UnifiedOutboxFacade.class).setArchiveOnly(Set.of(
                    "PLATFORM:FOOD_CART_CHANGED","PLATFORM:FOOD_DINING_SESSION_CHANGED"));
            var service = new CheckCartServiceImpl(template.getMapper(CheckCartMapper.class),
                    template.getMapper(CheckCartItemMapper.class),template.getMapper(CheckMapper.class),
                    template.getMapper(GuestSessionMapper.class),template.getMapper(SeatMapper.class),
                    template.getMapper(TableOccupancyMapper.class),template.getMapper(TableAreaMapper.class),
                    template.getMapper(TableMapper.class),c.getBean(ActionLogMapper.class),
                    c.getBean(IdempotencyService.class),(event,options) -> {
                        var result=outbox.publish(event,options);
                        if(fail.get()) throw new IllegalStateException("after-cart-publish");
                        return result;
                    },catalog,numbers -> Map.of("A",new StoreCard("A","Store","E",null,"ACTIVE",true,
                            null,null,null,null,0,0)),c.getBean(ObjectMapper.class),
                    c.getBean(PlatformTransactionManager.class),Clock.fixed(NOW,ZoneOffset.UTC));
            test.accept(new State(f,service,outage,fail));
        });
    }
    @Test void diningRoomAuthorityRequiresLiveOriginalPostpayCheckAndJoinsOwningTransaction() {
        run(t -> {
            t.f.sql().update("UPDATE fnb_check SET pay_mode='POSTPAY',service_state='OPEN' WHERE check_no='CHECK'");
            var cart=t.service.createCart("E","A","staff",new CreateCartReq("CHECK",null,null,"POS","DINE_IN",null),
                    "dining-cart").cart();
            t.f.tx().execute(tx -> { t.service.requireDiningRoomCart("E","A",cart.cartNo()); return null; });
            for(String state:List.of("SETTLING","RELEASED")) {
                t.f.sql().update("UPDATE fnb_check SET service_state=? WHERE check_no='CHECK'",state);
                assertThatThrownBy(() -> t.service.requireDiningRoomCart("E","A",cart.cartNo()))
                        .isInstanceOf(BizException.class);
            }
            t.f.sql().update("UPDATE fnb_check SET service_state='OPEN',pay_mode='PREPAY' WHERE check_no='CHECK'");
            assertThatThrownBy(() -> t.service.requireDiningRoomCart("E","A",cart.cartNo()))
                    .isInstanceOf(BizException.class);
            t.f.sql().update("UPDATE fnb_check SET pay_mode='POSTPAY',status='CLOSED' WHERE check_no='CHECK'");
            assertThatThrownBy(() -> t.service.requireDiningRoomCart("E","A",cart.cartNo()))
                    .isInstanceOf(BizException.class);
            assertThat(t.f.sql().queryForObject("SELECT COUNT(*) FROM fnb_order_batch",Integer.class)).isZero();
        });
    }
    @Test void diningRoomAuthorityKeepsOriginalCheckLockUntilPlacementTransactionCompletes() {
        run(t -> {
            t.f.sql().update("UPDATE fnb_check SET pay_mode='POSTPAY',service_state='OPEN' WHERE check_no='CHECK'");
            var cart=t.service.createCart("E","A","staff",new CreateCartReq("CHECK",null,null,"POS","DINE_IN",null),
                    "dining-cart").cart();
            try(var writer=Executors.newSingleThreadExecutor()) {
                var entered=new CountDownLatch(1);
                var pending=new AtomicReference<Future<Integer>>();
                t.f.tx().execute(tx -> {
                    t.service.requireDiningRoomCart("E","A",cart.cartNo());
                    pending.set(writer.submit(() -> { entered.countDown();
                        return t.f.sql().update("UPDATE fnb_check SET status='CLOSED' WHERE check_no='CHECK'"); }));
                    try { assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue(); }
                    catch(InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException(failure); }
                    assertThatThrownBy(() -> pending.get().get(100,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                    return null;
                });
                try { assertThat(pending.get().get(2,TimeUnit.SECONDS)).isOne(); }
                catch(Exception failure) { throw new IllegalStateException(failure); }
            }
        });
    }
    private static void create(JdbcTemplate sql,Class<?> type) {
        var info=TableInfoHelper.getTableInfo(type);
        if("true".equals(System.getenv("FOOD_TEST_DISPOSABLE"))
                && Set.of("fnb_check_cart","fnb_check_cart_item").contains(info.getTableName())) {
            try(var stream=new ClassPathResource(
                    "db/industry/food/V1__food_business_tables.sql").getInputStream()) {
                var ddl=Pattern.compile("CREATE TABLE `"+info.getTableName()
                        +"` \\(.*?\\) ENGINE=InnoDB[^;]*;",Pattern.DOTALL)
                        .matcher(new String(stream.readAllBytes(),StandardCharsets.UTF_8));
                assertThat(ddl.find()).isTrue(); sql.execute(ddl.group()); return;
            } catch(IOException failure) { throw new IllegalStateException(failure); }
        }
        var fields=info.getFieldList().stream().map(field -> {
            Class<?> java=field.getPropertyType();
            String data=java==Long.class?"BIGINT":java==Integer.class?"INT":java==LocalDateTime.class?"DATETIME(3)":
                    java==LocalDate.class?"DATE":java==BigDecimal.class?"DECIMAL(18,6)":java==byte[].class?
                    "VARBINARY(64)":java==Boolean.class?"BOOLEAN":"VARCHAR(255)";
            if(field.getColumn().equals("selection")) data="true".equals(System.getenv("FOOD_TEST_DISPOSABLE"))?
                    "JSON":"VARCHAR(100000)";
            if(field.getColumn().equals("cart_no") || field.getColumn().equals("source_line_ref")) data="VARCHAR(64)";
            if(Set.of("deleted","version").contains(field.getColumn())) data+=" DEFAULT 0";
            return field.getColumn()+" "+data;
        }).collect(Collectors.joining(","));
        sql.execute("CREATE TABLE "+info.getTableName()+" (id BIGINT AUTO_INCREMENT PRIMARY KEY,"+fields+")");
    }
    private static byte[] digest(String raw) {
        try { return MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static CatalogVO catalog(String store,String source,String fulfillment) {
        var dish=new DishVO("GOODS","SINGLE",0,new DishPresentation(null,List.of(),List.of(),List.of(),List.of()),
                new MeasurementPolicy("EA","COUNT",0,null),0,0);
        var sku=new CatalogSkuVO("SKU","Cup",null,500L,"CNY","KNOWN",10,"KNOWN",true,false,
                null,true,false,null);
        return new CatalogVO(store,source,fulfillment,NOW,List.of(new CatalogMenuVO("MENU","REV",1L,"Menu",0,
                null,List.of())),List.of(new CatalogItemVO("MENU","ITEM","CAT","Tea","GOODS","Tea",null,
                        dish,List.of(),List.of(sku))));
    }
    private static OrderLineInput line(String ref) {
        return new OrderLineInput(ref,"MENU","ITEM","REV","GOODS","SKU",BigDecimal.ONE,null,null,List.of(),List.of());
    }
    private static String cart(State t) {
        return t.service.createCart("E","A","staff",new CreateCartReq("CHECK",null,null,"POS","DINE_IN",null),
                "create").cart().cartNo();
    }
    private static CartAccess guest(String session) { return new CartAccess(session,"secret-"+session,null); }
    @Test void createsOneSharedCartAndKeepsSameSkuDifferentSourceLines() {
        run(t -> {
            String cart=cart(t);
            var second=t.service.createCart("E","A","other",
                    new CreateCartReq("CHECK",null,null,"WAITER", "DINE_IN",null),
                    "second-create");
            assertThat(second.cart().cartNo()).isEqualTo(cart);
            var first=t.service.replaceLine("E","A",cart,"L1","staff",new ReplaceCartLineReq(line("L1"),0L),"line1");
            t.service.replaceLine("E","A",cart,"L2","other",new ReplaceCartLineReq(line("L2"),1L),"line2");
            assertThat(t.service.getCart("E","A",cart).cart().lines()).extracting(OrderLineInput::sourceLineRef)
                    .containsExactly("L1","L2");
            t.outage.set(true);
            assertThat(t.service.replaceLine("E","A",cart,"L1","staff",new ReplaceCartLineReq(line("L1"),0L),"line1"))
                    .isEqualTo(first);
            assertThat(t.f.sql().queryForObject("SELECT COUNT(*) FROM fnb_check_cart",Integer.class)).isEqualTo(1);
        });
    }
    @Test void rejectsForeignStaleAndSubmittingWritesWithoutChangingDraft() {
        run(t -> {
            String cart=cart(t);
            assertThatThrownBy(() -> t.service.getCart("E","OTHER",cart)).isInstanceOf(BizException.class);
            t.service.replaceLine("E","A",cart,"L","staff",new ReplaceCartLineReq(line("L"),0L),"line");
            assertThatThrownBy(() -> t.service.replaceLine("E","A",cart,"L","other",
                    new ReplaceCartLineReq(line("L"),0L),"stale")).isInstanceOf(BizException.class);
            t.f.sql().update("UPDATE fnb_check_cart SET status='SUBMITTING'");
            assertThatThrownBy(() -> t.service.archiveLine("E","A",cart,"L","staff",new CartVersionReq(1L),"archive"))
                    .isInstanceOf(BizException.class);
            assertThatThrownBy(() -> t.service.archiveCart("E","A",cart,"staff",new CartVersionReq(1L),"close"))
                    .isInstanceOf(BizException.class);
            assertThat(t.service.getCart("E","A",cart).cart().lines()).hasSize(1);
        });
    }
    @Test void archivePreservesLineIdentityAndClosingPreservesRows() {
        run(t -> {
            String cart=cart(t);
            t.service.replaceLine("E","A",cart,"L","staff",new ReplaceCartLineReq(line("L"),0L),"add");
            t.service.archiveLine("E","A",cart,"L","staff",new CartVersionReq(1L),"remove");
            assertThat(t.service.getCart("E","A",cart).cart().lines()).isEmpty();
            t.service.replaceLine("E","A",cart,"L","other",new ReplaceCartLineReq(line("L"),2L),"readd");
            assertThat(t.f.sql().queryForObject("SELECT added_by FROM fnb_check_cart_item",String.class))
                    .isEqualTo("staff");
            t.service.archiveCart("E","A",cart,"staff",new CartVersionReq(3L),"close");
            assertThat(t.service.getCart("E","A",cart).cart().status()).isEqualTo("CLOSED");
            assertThat(t.f.sql().queryForObject("SELECT COUNT(*) FROM fnb_check_cart_item",Integer.class)).isEqualTo(1);
        });
    }
    @Test void tableMenuDoesNotGrantCartButStaffCanAuthorizeTwoGuestsToShare() {
        run(t -> {
            String cart=cart(t);
            assertThatThrownBy(() -> t.service.customerGetCart(cart,guest("G1"))).isInstanceOf(BizException.class);
            t.service.authorizeSession("E","A","CHECK","staff",new AuthorizeCartSessionReq("G1",0L),"grant1");
            t.service.authorizeSession("E","A","CHECK","staff",new AuthorizeCartSessionReq("G2",0L),"grant2");
            assertThat(t.service.customerCreateCart("A",guest("G1"),
                    new CreateCustomerCartReq("CHECK","G1","DINE_IN",null),"guest-create").cart().cartNo())
                    .isEqualTo(cart);
            t.service.customerReplaceLine(cart,"GUEST_LINE",guest("G2"),
                    new ReplaceCartLineReq(line("GUEST_LINE"),0L),"gadd");
            assertThat(t.service.customerGetCart(cart,guest("G1")).cart().lines()).hasSize(1);
            assertThatThrownBy(() -> t.service.customerGetCart(cart,guest("G3"))).isInstanceOf(BizException.class);
            t.f.sql().update("UPDATE fnb_guest_session SET revoked_at=CURRENT_TIMESTAMP WHERE session_no='G2'");
            assertThatThrownBy(() -> t.service.customerGetCart(cart,guest("G2"))).isInstanceOf(BizException.class);
        });
    }
    @Test void loggedInH5UsesRealBuyerAndRejectsAnotherBuyerWithoutCredential() {
        run(t -> {
            var owner=new CartAccess(null,null,"BUYER");
            var result=t.service.customerCreateCart("A",owner,
                    new CreateCustomerCartReq(null,null,"PICKUP",null),"online");
            assertThat(result.cart().orderSource()).isEqualTo("H5");
            assertThat(t.service.customerGetCart(result.cart().cartNo(),owner).cart().buyerNo()).isNull();
            assertThatThrownBy(() -> t.service.customerGetCart(result.cart().cartNo(),
                    new CartAccess(null,null,"OTHER")))
                    .isInstanceOf(BizException.class);
        });
    }
    @Test void failedOutboxRollsBackCartVersionRowsAuditAndReceipt() {
        run(t -> {
            String cart=cart(t);
            var tables=List.of("fnb_check_cart_item","fnb_action_log","sys_idempotent","sys_outbox");
            var before=tables.stream().map(x -> t.f.sql().queryForObject("SELECT COUNT(*) FROM "+x,
                    Long.class)).toList();
            t.fail.set(true);
            assertThatThrownBy(() -> t.service.replaceLine("E","A",cart,"L","staff",
                    new ReplaceCartLineReq(line("L"),0L),"fail")).isInstanceOf(IllegalStateException.class);
            assertThat(tables.stream().map(x -> t.f.sql().queryForObject("SELECT COUNT(*) FROM "+x,
                    Long.class)).toList())
                    .isEqualTo(before);
            assertThat(t.service.getCart("E","A",cart).cart().version()).isZero();
        });
    }
    @Test void guestOwnedSharedCartSurvivesFirstGuestsRevocation() {
        run(t -> {
            for(String guest:List.of("G1","G2")) t.service.authorizeSession("E","A","CHECK","staff",
                    new AuthorizeCartSessionReq(guest,0L),"grant-"+guest);
            String cart=t.service.customerCreateCart("A",guest("G1"),
                    new CreateCustomerCartReq("CHECK","G1","DINE_IN",null),"shared").cart().cartNo();
            t.f.sql().update("UPDATE fnb_guest_session SET revoked_at=CURRENT_TIMESTAMP WHERE session_no='G1'");
            t.service.customerReplaceLine(cart,"L",guest("G2"),
                    new ReplaceCartLineReq(line("L"),0L),"second");
            assertThat(t.service.customerGetCart(cart,guest("G2")).cart().version()).isEqualTo(1);
        });
    }
    @Test void staffCannotAttachUnrelatedSessionToCheck() {
        run(t -> {
            assertThatThrownBy(() -> t.service.createCart("E","A","staff",
                    new CreateCartReq("CHECK","G1",null,"POS","DINE_IN",null),"unauthorized-bind"))
                    .isInstanceOf(BizException.class);
            assertThat(t.f.sql().queryForObject("SELECT COUNT(*) FROM fnb_check_cart",Integer.class)).isZero();
        });
    }
    @Test void concurrentCreationAndCasHaveOneWinner() {
        run(t -> {
            var pool=Executors.newFixedThreadPool(2);
            try {
                var start=new CountDownLatch(1);
                var requests=List.of("one","two").stream().map(key -> pool.submit(() -> {
                    start.await();
                    return t.service.createCart("E","A","staff",
                            new CreateCartReq("CHECK",null,null,"POS","DINE_IN",null),key).cart().cartNo();
                })).toList();
                start.countDown();
                String cart=requests.getFirst().get(15,TimeUnit.SECONDS);
                assertThat(requests.getLast().get(15,TimeUnit.SECONDS)).isEqualTo(cart);
                var race=new CountDownLatch(1);
                var writes=List.of("L1","L2").stream().map(ref -> pool.submit(() -> {
                    race.await();
                    try {
                        t.service.replaceLine("E","A",cart,ref,"staff",new ReplaceCartLineReq(line(ref),0L),ref);
                        return true;
                    } catch(BizException failure) {
                        assertThat(failure.errorCode()).isEqualTo(ErrorCode.CONFLICT);
                        return false;
                    }
                })).toList();
                race.countDown();
                assertThat(writes.getFirst().get(15,TimeUnit.SECONDS))
                        .isNotEqualTo(writes.getLast().get(15,TimeUnit.SECONDS));
                assertThat(t.service.getCart("E","A",cart).cart().lines()).hasSize(1);
            } catch(Exception failure) { throw new AssertionError(failure); }
            finally { pool.shutdownNow(); }
        });
    }

    @Test void departedTableMembershipCannotCreateReadEditOrReplaySharedCart() {
        run(t -> {
            t.service.authorizeSession("E","A","CHECK","staff",new AuthorizeCartSessionReq("G1",0L),"grant");
            String cart=cart(t);
            t.service.customerReplaceLine(cart,"L",guest("G1"),new ReplaceCartLineReq(line("L"),0L),"accepted");
            // Transfer/partial-clear authority is current check/table membership, not a historic grant.
            t.f.sql().update("UPDATE fnb_table_occupancy SET ended_at=CURRENT_TIMESTAMP WHERE check_no='CHECK'");
            t.f.sql().update("INSERT INTO fnb_table_occupancy(entity_no,check_no,table_no,version,deleted) "
                    + "VALUES('E','OTHER_CHECK','TABLE',0,0)");
            assertThatThrownBy(() -> t.service.customerCreateCart("A",guest("G1"),
                    new CreateCustomerCartReq("CHECK","G1","DINE_IN",null),"after-transfer"))
                    .isInstanceOf(BizException.class).hasMessage("NOT_FOUND");
            assertThatThrownBy(() -> t.service.customerGetCart(cart,guest("G1")))
                    .isInstanceOf(BizException.class).hasMessage("NOT_FOUND");
            assertThatThrownBy(() -> t.service.customerReplaceLine(cart,"L",guest("G1"),
                    new ReplaceCartLineReq(line("L"),0L),"accepted"))
                    .isInstanceOf(BizException.class).hasMessage("NOT_FOUND");
            assertThatThrownBy(() -> t.service.customerArchiveLine(cart,"L",guest("G1"),
                    new CartVersionReq(1L),"after-transfer-remove"))
                    .isInstanceOf(BizException.class).hasMessage("NOT_FOUND");
            assertThat(t.service.getCart("E","A",cart).cart().version()).isEqualTo(1L);
        });
    }

}
