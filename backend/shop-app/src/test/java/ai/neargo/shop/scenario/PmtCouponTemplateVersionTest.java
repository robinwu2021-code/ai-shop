package ai.neargo.shop.scenario;

import ai.neargo.shop.promotion.dto.CouponVOs.CouponSaveCmd;
import ai.neargo.shop.promotion.entity.PmtCoupon;
import ai.neargo.shop.promotion.service.CouponService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class PmtCouponTemplateVersionTest {
    @Autowired CouponService coupons;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;

    @Test
    void editKeepsTheOriginalDefinitionAndStoreScopeUnderTheSameCouponNumber() {
        String entity = "M-CV-STORE";
        StoreOwnerFixture.entity(jdbc, entity);
        String coupon = coupons.save(entity, storeCoupon(null, 100L, List.of("S1", "S2")), "OP").couponNo();
        String original = version(coupon);
        assertThat(coupons.save(entity, storeCoupon(coupon, 250L, List.of("S2", "S3")), "OP").couponNo())
                .isEqualTo(coupon);
        String edited = version(coupon);
        assertThat(edited).isNotNull().isNotEqualTo(original);
        assertThat(amount(original)).isEqualTo(100L);
        assertThat(amount(edited)).isEqualTo(250L);
        assertThat(stores(coupon, original)).containsExactly("S1", "S2");
        assertThat(stores(coupon, edited)).containsExactly("S2", "S3");
    }

    @Test
    void firstClaimCapturesAnUnusedLegacyTemplateButNeverLabelsAnAlreadyIssuedPool() {
        String entity = "M-CV-LEGACY";
        StoreOwnerFixture.entity(jdbc, entity);
        String fresh = "PC-CV-LEGACY-FRESH";
        String used = "PC-CV-LEGACY-USED";
        for (String coupon : List.of(fresh, used)) {
            jdbc.update("""
                    insert into pmt_coupon(coupon_no,entity_no,title,benefit_value,validity_mode,
                      valid_days,total_count,received_count,created_at,updated_at)
                    values(?,?,?,100,'RELATIVE',7,10,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                    """, coupon, entity, coupon, coupon.equals(used) ? 1 : 0);
        }
        coupons.receive("U-CV-FRESH", fresh);
        coupons.receive("U-CV-USED", used);
        assertThat(version(fresh)).isNotNull();
        assertThat(jdbc.queryForObject("select template_version_no from pmt_user_coupon where coupon_no=?",
                String.class, fresh)).isEqualTo(version(fresh));
        assertThat(version(used)).isNull();
        assertThat(jdbc.queryForObject("select count(*) from pmt_coupon_version where coupon_no=?",
                Integer.class, used)).isZero();
    }

    private String version(String coupon) {
        return jdbc.queryForObject("select template_version_no from pmt_coupon where coupon_no=?", String.class, coupon);
    }

    private long amount(String version) {
        String snapshot = jdbc.queryForObject("select rule_snapshot from pmt_coupon_version where version_no=?",
                String.class, version);
        return json.readTree(snapshot).get("benefitValue").asLong();
    }

    private List<String> stores(String coupon, String version) {
        return jdbc.queryForList("""
                select ref_no from pmt_activity_scope
                where tenant_no='MAIN' and owner_type='ENT_TEMPLATE' and owner_no=? and version_no=?
                and scope_dimension='TARGET' and scope_kind='STORE' and included=1 order by sort_no
                """, String.class, coupon, version);
    }

    private static CouponSaveCmd storeCoupon(String no, long amount, List<String> stores) {
        return new CouponSaveCmd(no, "门店券", PmtCoupon.CASH, amount, null, null, 0L, null,
                PmtCoupon.SCOPE_STORE, stores, null, PmtCoupon.RELATIVE, null, null, 7,
                PmtCoupon.ISSUE_CENTER, PmtCoupon.REDEEM_ORDER, 1, 10, 1, null);
    }
}
