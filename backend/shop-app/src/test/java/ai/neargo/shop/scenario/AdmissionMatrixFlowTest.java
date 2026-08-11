package ai.neargo.shop.scenario;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.Fulfillments;
import ai.neargo.shop.community.entity.CmtPickupPoint;
import ai.neargo.shop.merchant.entity.MchEntity;
import ai.neargo.shop.merchant.mapper.MerchantMappers.MchEntityMapper;
import ai.neargo.shop.spi.user.AdmissionPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 准入矩阵（落地清单 F-2 / F-3，方案 §7.7）。
 *
 * <p>核心命题：<b>平台责任 = 供货方风险 × 交付留痕缺失</b>。
 * 平台无仓、不碰货，所以资质越弱就越依赖交付环节有独立第三方留痕。
 *
 * <p>要盯住的是<b>降级规则</b>：它是这套设计里唯一「一条规则替代一族枚举值」的地方 ——
 * 供货方就是自提点运营者时，那道独立核销并不存在，T 降一级后重查同一张矩阵。
 * 往后再出现「供货商同时是配送员」这类组合，矩阵不用改。
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("准入矩阵：S × T 决定准不准，降级规则替代一族枚举值")
class AdmissionMatrixFlowTest {

    @Autowired
    private AdmissionPort admissionPort;

    @Autowired
    private MchEntityMapper merchantMapper;

    @Autowired
    private ai.neargo.shop.community.mapper.CommunityMappers.PickupPointMapper pickupMapper;

    @Test
    @DisplayName("★★ S3 × T1（小微 + 商家自送）→ 拒（70014）")
    void microSelfDeliveryDenied() {
        String micro = merchantOf("MICRO", null);

        assertThatThrownBy(() ->
                admissionPort.requireFulfillmentAllowed(micro, Fulfillments.MERCHANT_DELIVERY, null))
                .as("弱主体 + 交付零留痕 = 出事只有平台兜底，而平台连货都没碰过")
                .isInstanceOf(BizException.class)
                .hasMessage(ErrorCode.FULFILLMENT_TIER_DENIED.name());
    }

    @Test
    @DisplayName("★ S3 走快递 / 自提都放行 —— 给最弱一档留的路不能堵死")
    void microStillHasAPath() {
        String micro = merchantOf("MICRO", null);

        assertThatCode(() -> admissionPort.requireFulfillmentAllowed(micro, Fulfillments.EXPRESS, null))
                .as("保证金 + 限品类 + 限额由 F-6 的策略表管，矩阵只回答「这个组合准不准」")
                .doesNotThrowAnyException();
        assertThatCode(() ->
                admissionPort.requireFulfillmentAllowed(micro, Fulfillments.STORE_PICKUP, null))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("★ S1 三种履约全放行 —— 矩阵对企业主体是透明的")
    void enterpriseUnaffected() {
        String enterprise = merchantOf("ENTERPRISE", null);

        for (String f : new String[]{Fulfillments.EXPRESS, Fulfillments.STORE_PICKUP,
                Fulfillments.MERCHANT_DELIVERY}) {
            assertThatCode(() -> admissionPort.requireFulfillmentAllowed(enterprise, f, null))
                    .as("履约方式 %s", f)
                    .doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("★★ 降级：小微邻居供货 + 自家自提点 → 由 T2 降到 T1，被拒")
    void neighborSupplierAtOwnPickupIsDegradedAndDenied() {
        String ownerUser = "U-NB-" + System.nanoTime() % 1_000_000;
        String micro = merchantOf("MICRO", ownerUser);
        String pickup = neighborPickup(ownerUser);

        /*
         * 不带自提点时 S3×T2 是放行的；带上「自家的邻居自提点」之后，
         * 那道独立核销不存在了 —— 自己发货、自己核销 —— 于是降到 T1，落进 DENY 格。
         * 这正是「邻居供货」不需要成为一个枚举值的原因：它是矩阵里的一格加一条降级规则。
         */
        assertThatCode(() ->
                admissionPort.requireFulfillmentAllowed(micro, Fulfillments.NEIGHBOR_PICKUP, null))
                .doesNotThrowAnyException();

        assertThatThrownBy(() ->
                admissionPort.requireFulfillmentAllowed(micro, Fulfillments.NEIGHBOR_PICKUP, pickup))
                .isInstanceOf(BizException.class)
                .hasMessage(ErrorCode.FULFILLMENT_TIER_DENIED.name());
    }

    @Test
    @DisplayName("★★ 降级：个体户邻居供货 + 自家自提点 → 放行，但必须买家确认收货")
    void individualNeighborSupplierNeedsBuyerConfirm() {
        String ownerUser = "U-NB2-" + System.nanoTime() % 1_000_000;
        String individual = merchantOf("INDIVIDUAL", ownerUser);
        String pickup = neighborPickup(ownerUser);

        boolean needsConfirm = admissionPort.requireFulfillmentAllowed(
                individual, Fulfillments.NEIGHBOR_PICKUP, pickup);

        assertThat(needsConfirm)
                .as("核销那个第三方已经不独立了，只剩买家能证明货真的到了手上")
                .isTrue();
    }

    @Test
    @DisplayName("★★ 别人家的自提点不触发降级 —— 降级判的是「是不是同一个人」")
    void otherPersonsPickupDoesNotDegrade() {
        String micro = merchantOf("MICRO", "U-OWNER-A");
        String pickup = neighborPickup("U-OWNER-B");

        assertThatCode(() ->
                admissionPort.requireFulfillmentAllowed(micro, Fulfillments.NEIGHBOR_PICKUP, pickup))
                .as("邻居自提点是常态，不能因为「是邻居点」就一律降级 —— "
                        + "降级的判据是供货方与运营者是同一个人")
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("★ 未降级的 S2 × T1 不强制确认 —— 商家自送本来就是正常经营形态")
    void plainSelfDeliveryNeedsNoConfirm() {
        String individual = merchantOf("INDIVIDUAL", null);

        boolean needsConfirm = admissionPort.requireFulfillmentAllowed(
                individual, Fulfillments.MERCHANT_DELIVERY, null);

        assertThat(needsConfirm)
                .as("给每一单都加确认会把它变成人人都要点的噪音，"
                        + "而噪音多了真正需要确认的那一单就没人看")
                .isFalse();
    }

    @Test
    @DisplayName("★ 认不出的主体类型或履约方式一律放行 —— 与本域其余判定同向")
    void unknownInputsPassThrough() {
        String unknown = merchantOf("CONSIGNMENT", null);

        assertThatCode(() ->
                admissionPort.requireFulfillmentAllowed(unknown, Fulfillments.MERCHANT_DELIVERY, null))
                .doesNotThrowAnyException();
        assertThatCode(() ->
                admissionPort.requireFulfillmentAllowed(merchantOf("MICRO", null), "DRONE", null))
                .doesNotThrowAnyException();
    }

    // ---------------------------------------------------------------- helpers

    private String merchantOf(String legalForm, String ownerUserNo) {
        String no = "MMX" + System.nanoTime() % 10_000_000;
        MchEntity m = new MchEntity();
        m.setEntityNo(no);
        m.setName("矩阵测试-" + legalForm);
        m.setLegalForm(legalForm);
        m.setOwnerUserNo(ownerUserNo);
        m.setStatus("ACTIVE");
        merchantMapper.insert(m);
        assertThat(merchantMapper.selectOne(Wrappers.<MchEntity>lambdaQuery()
                .eq(MchEntity::getEntityNo, no).last("LIMIT 1"))).isNotNull();
        return no;
    }

    /** 邻居自提点：{@code owner_ref} 存的是**用户号**，不是门店号。 */
    private String neighborPickup(String ownerUserNo) {
        String no = "PPX" + System.nanoTime() % 10_000_000;
        CmtPickupPoint p = new CmtPickupPoint();
        p.setPickupNo(no);
        p.setCommunityNo("C0001");
        p.setName("矩阵测试点");
        p.setType("NEIGHBOR");
        p.setOwnerRef(ownerUserNo);
        p.setStatus("ACTIVE");
        pickupMapper.insert(p);
        return no;
    }
}
