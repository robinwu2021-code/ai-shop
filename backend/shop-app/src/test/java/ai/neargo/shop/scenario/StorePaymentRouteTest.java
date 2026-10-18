package ai.neargo.shop.scenario;

import ai.neargo.shop.spi.user.MerchantQueryPort;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Real merchant/store rows, with synthetic identifiers and no external payment calls. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:store-payment-route;"
        + "MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
class StorePaymentRouteTest {
    @Autowired private MerchantQueryPort merchants;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void same_entity_stores_keep_their_explicit_accounts_and_actual_versions() {
        var f = fixture();
        var first = route(f.entity(), f.first());
        var second = route(f.entity(), f.second());
        assertThat(first.storeNo()).isEqualTo(f.first());
        assertThat(first.merchantNo()).isEqualTo(f.entity());
        assertThat(first.payMerchantNo()).isEqualTo(f.payFirst());
        assertThat(first.subMerchantId()).isEqualTo("synthetic-sub-first");
        assertThat(first.accountVersion()).isEqualTo(f.payFirst() + ":7");
        assertThat(first.fundsPath()).isEqualTo("DIRECT");
        assertThat(second.storeNo()).isEqualTo(f.second());
        assertThat(second.payMerchantNo()).isEqualTo(f.paySecond());
        assertThat(second.accountVersion()).isEqualTo(f.paySecond() + ":11");
        assertThat(first.channel()).isEqualTo("WECHAT");
    }

    @Test
    void no_store_binding_never_falls_back_to_the_entity_default_or_another_store() {
        var f = fixture();
        jdbc.update("UPDATE mch_store SET pay_merchant_no=NULL WHERE store_no=?", f.second());
        assertThat(merchants.paymentRouteOf(f.entity(), f.second(), "WECHAT")).isEmpty();
        assertThat(merchants.paymentRouteOf(f.entity(), "missing-store", "WECHAT")).isEmpty();
        assertThat(merchants.paymentRouteOf(f.entity(), null, "WECHAT")).isEmpty();
    }

    @Test
    void foreign_owner_wrong_channel_and_unavailable_accounts_are_rejected() {
        var f = fixture();
        assertThat(merchants.paymentRouteOf("other-owner", f.first(), "WECHAT")).isEmpty();
        assertThat(merchants.paymentRouteOf(f.entity(), f.first(), "ALIPAY")).isEmpty();
        jdbc.update("UPDATE mch_payment_merchant SET entity_no=? WHERE pay_merchant_no=?",
                "foreign-owner", f.payFirst());
        assertThat(merchants.paymentRouteOf(f.entity(), f.first(), "WECHAT")).isEmpty();
        jdbc.update("UPDATE mch_payment_merchant SET apply_status='FROZEN' WHERE pay_merchant_no=?",
                f.paySecond());
        assertThat(merchants.paymentRouteOf(f.entity(), f.second(), "WECHAT")).isEmpty();
    }

    @Test
    void captured_route_does_not_change_when_the_store_or_account_configuration_changes() {
        var f = fixture();
        var captured = route(f.entity(), f.first());
        jdbc.update("UPDATE mch_store SET pay_merchant_no=? WHERE store_no=?", f.paySecond(), f.first());
        jdbc.update("UPDATE mch_payment_merchant SET version=8,sub_mchid='changed' WHERE pay_merchant_no=?",
                f.payFirst());
        assertThat(captured.subMerchantId()).isEqualTo("synthetic-sub-first");
        assertThat(captured.accountVersion()).isEqualTo(f.payFirst() + ":7");
        assertThat(route(f.entity(), f.first()).payMerchantNo())
                .isEqualTo(f.paySecond());
    }

    @Test
    void missing_channel_identity_or_unknown_funds_path_cannot_manufacture_a_route() {
        var f = fixture();
        jdbc.update("UPDATE mch_payment_merchant SET sub_mchid=' ' WHERE pay_merchant_no=?", f.payFirst());
        assertThat(merchants.paymentRouteOf(f.entity(), f.first(), "WECHAT")).isEmpty();
        jdbc.update("UPDATE mch_entity SET funds_mode='UNKNOWN' WHERE entity_no=?", f.entity());
        assertThat(merchants.paymentRouteOf(f.entity(), f.second(), "WECHAT")).isEmpty();
    }

    @Test
    void aggregated_path_and_archived_subjects_are_resolved_without_direct_defaults() {
        var f = fixture();
        jdbc.update("UPDATE mch_entity SET funds_mode='AGGREGATED' WHERE entity_no=?", f.entity());
        assertThat(route(f.entity(), f.first()).fundsPath())
                .isEqualTo("AGGREGATED");
        jdbc.update("UPDATE mch_store SET status='SUSPENDED' WHERE store_no=?", f.first());
        assertThat(merchants.paymentRouteOf(f.entity(), f.first(), "WECHAT")).isEmpty();
        jdbc.update("UPDATE mch_payment_merchant SET deleted=1 WHERE pay_merchant_no=?", f.paySecond());
        assertThat(merchants.paymentRouteOf(f.entity(), f.second(), "WECHAT")).isEmpty();
    }

    private MerchantQueryPort.PaymentRoute route(String entity, String store) {
        var result = merchants.paymentRouteOf(entity, store, "WECHAT");
        assertThat(result).as("explicit active store route").isPresent();
        return result.orElseThrow();
    }

    private Fixture fixture() {
        String id = UUID.randomUUID().toString().substring(0, 8);
        var f = new Fixture("route-e-" + id, "route-s1-" + id, "route-s2-" + id,
                "route-p1-" + id, "route-p2-" + id);
        jdbc.update("INSERT INTO mch_entity(entity_no,name,funds_mode,created_at,updated_at)"
                + " VALUES(?,?,'DIRECT',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", f.entity(), "synthetic");
        store(f.entity(), f.first(), f.payFirst());
        store(f.entity(), f.second(), f.paySecond());
        account(f.entity(), f.first(), f.payFirst(), "synthetic-sub-first", 7);
        account(f.entity(), f.second(), f.paySecond(), "synthetic-sub-second", 11);
        return f;
    }

    private void store(String entity, String store, String pay) {
        jdbc.update("INSERT INTO mch_store(entity_no,store_no,pay_merchant_no,created_at,updated_at)"
                + " VALUES(?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", entity, store, pay);
    }

    private void account(String entity, String store, String pay, String sub, long version) {
        jdbc.update("INSERT INTO mch_payment_merchant(entity_no,store_no,pay_merchant_no,pay_channel,"
                + "sub_mchid,apply_status,version,created_at,updated_at)"
                + " VALUES(?,?,?,'WECHAT',?,'ACTIVE',?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                entity, store, pay, sub, version);
    }

    private record Fixture(String entity, String first, String second, String payFirst, String paySecond) {
    }
}
