package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementUseSourcePort;
import ai.neargo.shop.spi.entitlement.SourceRef;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/** Actual source-order, identity/store and legacy ownership rows, without a fake source provider. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:ent-order-facts;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
@ActiveProfiles("test")
class EntitlementOrderSourceFactsFlowTest {
    @Autowired private EntitlementUseSourcePort sources;
    @Autowired private JdbcTemplate jdbc;
    private String order;
    private String sub;
    private String user;
    private String entity;
    private String store;
    private EntitlementContext context;

    @BeforeEach
    void setup() {
        String id = UUID.randomUUID().toString();
        order = "OF-O" + id;
        sub = "OF-SO" + id;
        user = "OF-U" + id;
        entity = "OF-M" + id;
        store = "OF-S" + id;
        context = new EntitlementContext("MAIN", "PAY", "FACTS", "a".repeat(64));
        jdbc.update("INSERT INTO usr_account(user_no,status,tenant_no,created_at,updated_at) "
                + "VALUES (?,'NORMAL','MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", user);
        jdbc.update("INSERT INTO mch_entity(entity_no,owner_user_no,name,status,tenant_no,created_at,updated_at) "
                + "VALUES (?,?,'owner facts','ACTIVE','MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", entity, user);
        jdbc.update("INSERT INTO mch_store(store_no,entity_no,name,status,tenant_no,created_at,updated_at) "
                + "VALUES (?,?,'facts store','ACTIVE','MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", store, entity);
        jdbc.update("INSERT INTO ord_order(order_no,user_no,status,pay_amount,goods_amount,freight_amount,"
                + "discount_amount,currency,tenant_no,created_at,updated_at) VALUES (?,?,'WAIT_PAY',900,1000,0,100,"
                + "'CNY','MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", order, user);
        jdbc.update("INSERT INTO ord_sub_order(sub_order_no,order_no,user_no,entity_no,store_no,status,fulfillment,"
                + "goods_amount,freight_amount,discount_amount,pay_amount,tenant_no,created_at,updated_at) "
                + "VALUES (?,?,?,?,?,'WAIT_PAY','SERVICE',1000,0,100,900,'MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                sub, order, user, entity, store);
        jdbc.update("INSERT INTO ord_item(sub_order_no,order_no,goods_no,sku_no,title,qty,price,amount,paid_share_minor,"
                + "tenant_no,created_at,updated_at) VALUES (?,?,'GOODS1','SKU1','Care',1,1000,1000,1,'MAIN',"
                + "CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", sub, order);
    }
    private SourceRef source() { return new SourceRef("CORE", "ORDER_USE", sub, null, null, null, 0); }

    @Test
    void readsPostActivityBaseFromOrderFactsAndIgnoresTheAlreadyNetPaidShare() {
        var fact = sources.resolve(context, source());
        assertThat(fact.userNo()).isEqualTo(user);
        assertThat(fact.entityNo()).isEqualTo(entity);
        assertThat(fact.storeNo()).isEqualTo(store);
        assertThat(fact.items()).hasSize(1);
        assertThat(fact.items().getFirst().amountMinor()).isEqualTo(900);
        assertThat(fact.items().getFirst().itemNo()).isEqualTo("GOODS1");
        assertThat(fact.orderSnapshotHash()).matches("[0-9a-f]{64}");
        jdbc.update("UPDATE ord_order SET version=1 WHERE order_no=?", order);
        assertThatThrownBy(() -> sources.resolve(context, source())).isInstanceOf(BizException.class);
    }

    @Test
    void missingCouponTitleCannotHideAnActiveLegacyBinding() {
        jdbc.update("INSERT INTO mkt_user_coupon(user_coupon_no,coupon_no,user_no,status,order_no,tenant_no,"
                + "created_at,updated_at,received_at) VALUES (?,'DELETED-TEMPLATE',?,'USED',?,'MAIN',"
                + "CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,1)", "UC" + UUID.randomUUID(), user, order);
        assertThatThrownBy(() -> sources.resolve(context, source())).isInstanceOf(BizException.class);
    }

    @Test
    void anUnknownOrForeignTenantParentCannotBecomeAnEligibleCategoryAncestor() {
        String child = "OF-C" + UUID.randomUUID();
        String parent = "OF-P" + UUID.randomUUID();
        jdbc.update("INSERT INTO prd_category(category_no,parent_no,name,status,tenant_no,created_at,updated_at) "
                + "VALUES (?,?,'child','ACTIVE','MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", child, parent);
        jdbc.update("INSERT INTO prd_category(category_no,name,status,tenant_no,created_at,updated_at) "
                + "VALUES (?,'foreign parent','ACTIVE','OTHER',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", parent);
        jdbc.update("UPDATE ord_item SET category_no=? WHERE order_no=?", child, order);
        var fact = sources.resolve(context, source());
        assertThat(fact.categoryRefsByLine().get(fact.items().getFirst().orderLineNo())).containsExactly(child);
    }

    @Test
    void crossTenantDisabledIdentityAndReadOnlyStoreAreRejectedBeforeNewHolds() {
        var other = new EntitlementContext("OTHER", "PAY", "FACTS", "a".repeat(64));
        assertThatThrownBy(() -> sources.resolve(other, source())).isInstanceOf(BizException.class);
        jdbc.update("UPDATE usr_account SET status='DISABLED' WHERE user_no=?", user);
        assertThatThrownBy(() -> sources.resolve(context, source())).isInstanceOf(BizException.class);
        jdbc.update("UPDATE usr_account SET status='NORMAL' WHERE user_no=?", user);
        jdbc.update("UPDATE mch_store SET status='READONLY' WHERE store_no=?", store);
        assertThatThrownBy(() -> sources.resolve(context, source())).isInstanceOf(BizException.class);
    }
}
