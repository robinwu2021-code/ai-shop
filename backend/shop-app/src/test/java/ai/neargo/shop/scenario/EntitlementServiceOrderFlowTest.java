package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.member.dto.MemberCodeScope;
import ai.neargo.shop.member.service.MemberCodeResolveService;
import ai.neargo.shop.paybridge.EntitlementServiceUseCoordinator;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementDefinition;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort;
import ai.neargo.shop.trade.dto.AuthorizationCommand;
import ai.neargo.shop.trade.dto.ServiceOrderCreate;
import ai.neargo.shop.trade.dto.ServiceUseSubmit;
import ai.neargo.shop.trade.service.EntitlementAuthorizationService;
import ai.neargo.shop.trade.service.EntitlementServiceOrderService;
import java.util.List;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.ObjectMapper;

/** Actual service SKU, scoped member identity, customer consent, source decision and pay ledger. */
@SpringBootTest(properties = {"shop.member-code.enabled=true", "shop.core.entitlement.authorization.enabled=true",
        "shop.pay.entitlement.use.enabled=true",
        "spring.datasource.url=jdbc:h2:mem:ent-service-order;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
@ActiveProfiles("test")
class EntitlementServiceOrderFlowTest {
    @Autowired EntitlementServiceOrderService orders;
    @Autowired EntitlementServiceUseCoordinator coordinator;
    @Autowired EntitlementAuthorizationService authorizations;
    @Autowired MemberCodeResolveService members;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @MockitoSpyBean EntitlementUsePort pay;
    private String user;
    private String actor;
    private String entity;
    private String store;
    private String goods;
    private String sku;
    private String instance;
    private EntitlementContext context;
    private ServiceOrderCreate command;
    private String id() { return UUID.randomUUID().toString(); }
    @BeforeEach void setup() {
        user = id(); actor = id(); entity = id(); store = id(); goods = id(); sku = id(); instance = id();
        context = new EntitlementContext("MAIN", actor, id(), "a".repeat(64));
        for (String account : List.of(user, actor)) {
            jdbc.update("INSERT INTO usr_account(user_no,status,tenant_no,created_at,updated_at) "
                    + "VALUES (?,'NORMAL','MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", account);
        }
        jdbc.update("INSERT INTO mch_entity(entity_no,owner_user_no,name,status,market,tenant_no,created_at,updated_at) "
                + "VALUES (?,?,'service merchant','ACTIVE','CN','MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", entity, actor);
        jdbc.update("INSERT INTO mch_store(store_no,entity_no,name,status,tenant_no,created_at,updated_at) "
                + "VALUES (?,?,'service store','ACTIVE','MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", store, entity);
        jdbc.update("INSERT INTO prd_goods(goods_no,entity_no,title,type,on_sale,audit_status,fulfillments,tenant_no,"
                + "created_at,updated_at) VALUES (?,?,'swimming','SERVICE',1,'APPROVED','[\"STORE_VERIFY\"]',"
                + "'MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", goods, entity);
        jdbc.update("INSERT INTO prd_sku(sku_no,goods_no,entity_no,price,stock,locked_stock,tenant_no,created_at,updated_at) "
                + "VALUES (?,?,?,1000,0,0,'MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", sku, goods, entity);
        var rule = new EntitlementDefinition("30 times", "PAID_CARD", "MERCHANT", "CNY", "SERVICE_UNITS",
                null, null, null, null, null, "COUNT", 30, null,
                new EntitlementDefinition.Validity("RELATIVE", null, null, 30, "FIRST_USE", "ROLLING_DAYS", "UTC"),
                new EntitlementDefinition.RedemptionScope("STORES", List.of(store)), List.of("PAID_ORDER"));
        jdbc.update("INSERT INTO ent_instance(instance_no,template_version_no,grant_no,source_unit_key,user_no,"
                + "entity_no,kind,status,rule_snapshot,unit_index,total_units,remaining_units,reserved_units,asset_seq,"
                + "tenant_no,created_at,updated_at,created_by,updated_by) VALUES (?,'V1',?,?,?,?,'PAID_CARD',"
                + "'PENDING_ACTIVATION',?,1,30,30,0,1,'MAIN',?,?,'TEST','TEST')",
                instance, id(), id() + "a".repeat(28), user, entity, json.writeValueAsString(rule),
                LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1), LocalDateTime.now(ZoneOffset.UTC));
        var code = members.issue("MAIN", user);
        var reference = members.resolve(new MemberCodeScope("MAIN", entity, store, actor), code.memberCode());
        command = new ServiceOrderCreate(reference.customerRef(), store, List.of(new ServiceOrderCreate.Line(sku, 1)));
    }
    @Test void customerConfirmedServiceConsumesOneUnitAndCreatesNoCashOrderOrCashPayment() {
        var order = orders.create(context, entity, command, "service");
        assertThat(orders.create(context, entity, command, "service")).isEqualTo(order);
        var preview = coordinator.preview(context, entity, store, order.serviceOrderNo(), instance);
        assertThat(preview.redeemable()).isTrue();
        assertThat(jdbc.queryForObject("SELECT reserved_units FROM ent_instance WHERE instance_no=?", Integer.class,
                instance)).isZero();
        assertThatThrownBy(() -> authorizations.confirm("MAIN", actor, order.serviceOrderNo(),
                new AuthorizationCommand(0, preview.previewRef(), preview.snapshotHash(), id())))
                .isInstanceOf(BizException.class);
        var auth = authorizations.confirm("MAIN", user, order.serviceOrderNo(),
                new AuthorizationCommand(0, preview.previewRef(), preview.snapshotHash(), id()));
        var bypass = new EntitlementUsePort.UseCommand(order.operationNo(), order.fact().source(), user, entity, store,
                "CNY", EntitlementUsePort.AssetKind.PAID_CARD, instance, null,
                order.fact().serviceAt().atZone(ZoneOffset.UTC).toLocalDate(), 1, null, order.fact().items(),
                auth.authorizationRef(), null, preview.assetRevision(), preview.previewRef(), null);
        assertThatThrownBy(() -> pay.prepare(context, bypass)).isInstanceOf(BizException.class);
        var submit = new ServiceUseSubmit(order.serviceOrderNo(), preview.previewRef(), auth.authorizationRef(),
                preview.assetRevision());
        var result = coordinator.submit(context, entity, store, submit);
        assertThat(result.status()).isEqualTo(EntitlementUsePort.State.COMMITTED);
        assertThat(coordinator.submit(context, entity, store, submit)).isEqualTo(result);
        assertThat(jdbc.queryForObject("SELECT remaining_units FROM ent_instance WHERE instance_no=?", Integer.class,
                instance)).isEqualTo(29);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ent_usage WHERE source_order_no=?", Integer.class,
                order.serviceOrderNo())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ord_order WHERE order_no=?", Integer.class,
                order.serviceOrderNo())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stl_payment WHERE order_no=?", Integer.class,
                order.serviceOrderNo())).isZero();
    }
    @Test void sourceCancellationBeforeThePayPreparePermanentlyFencesTheOriginalCommand() {
        var order = orders.create(context, entity, command, "late");
        var preview = coordinator.preview(context, entity, store, order.serviceOrderNo(), instance);
        var auth = authorizations.confirm("MAIN", user, order.serviceOrderNo(),
                new AuthorizationCommand(0, preview.previewRef(), preview.snapshotHash(), id()));
        var staged = orders.stage(context, entity, store, new ServiceUseSubmit(order.serviceOrderNo(),
                preview.previewRef(), auth.authorizationRef(), preview.assetRevision()));
        assertThat(coordinator.cancel(context, entity, store, order.serviceOrderNo(), "customer declined").status())
                .isEqualTo(EntitlementUsePort.State.RELEASED);
        assertThat(pay.prepare(staged.context(), staged.command()).status()).isEqualTo(EntitlementUsePort.State.RELEASED);
        assertThat(jdbc.queryForObject("SELECT remaining_units FROM ent_instance WHERE instance_no=?", Integer.class,
                instance)).isEqualTo(30);
        assertThat(jdbc.queryForObject("SELECT reserved_units FROM ent_instance WHERE instance_no=?", Integer.class,
                instance)).isZero();
    }
    @Test void aLostPrepareReplyKeepsTheOriginalHoldAndCanFinishAfterTheConsentExpires() {
        var order = orders.create(context, entity, command, "lost");
        var preview = coordinator.preview(context, entity, store, order.serviceOrderNo(), instance);
        var auth = authorizations.confirm("MAIN", user, order.serviceOrderNo(),
                new AuthorizationCommand(0, preview.previewRef(), preview.snapshotHash(), id()));
        var once = new AtomicBoolean(true);
        doAnswer(call -> {
            Object answer = call.callRealMethod();
            if (once.getAndSet(false)) throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
            return answer;
        }).when(pay).prepare(any(), any());
        var result = coordinator.submit(context, entity, store, new ServiceUseSubmit(order.serviceOrderNo(),
                preview.previewRef(), auth.authorizationRef(), preview.assetRevision()));
        assertThat(result.status()).isEqualTo(EntitlementUsePort.State.PROCESSING);
        assertThat(jdbc.queryForObject("SELECT reserved_units FROM ent_instance WHERE instance_no=?", Integer.class,
                instance)).isEqualTo(1);
        jdbc.update("UPDATE ord_ent_authorization SET created_at=DATEADD('DAY',-2,CURRENT_TIMESTAMP),"
                + "authorized_at=DATEADD('DAY',-2,CURRENT_TIMESTAMP),expires_at=DATEADD('DAY',-1,CURRENT_TIMESTAMP) "
                + "WHERE authorization_no=?", auth.authorizationRef());
        assertThat(coordinator.recover(context, entity, order.operationNo()).status())
                .isEqualTo(EntitlementUsePort.State.COMMITTED);
        assertThat(jdbc.queryForObject("SELECT remaining_units FROM ent_instance WHERE instance_no=?", Integer.class,
                instance)).isEqualTo(29);
    }
}
