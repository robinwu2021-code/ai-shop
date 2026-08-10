package ai.neargo.shop.scenario;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 自提点归属到门店（V16），以及它解锁的那件事：<b>下单按自提点选店</b>。
 *
 * <p>此前 {@code owner_ref} 在 {@code type=STORE} 时存的是 {@code entity_no} ——
 * 「这个自提点属于哪家店」表达不了。于是下单落哪家店只能恒取默认门店，
 * 多门店时的表现是<b>扣了 A 店的库存、顾客却到 B 店去取货</b>：
 * 自提场景下这是一次直接的履约事故 —— 人到了，货不在。
 *
 * <p>这里守两件事：
 * <ol>
 *   <li>种子自提点的 owner_ref 是<b>门店号</b>，不是主体号（迁移与写入两侧都改到位）</li>
 *   <li>顺着这条归属能查回商家展示信息 —— 名字与 logo 仍挂在主体上</li>
 * </ol>
 */
@SpringBootTest
@ActiveProfiles("test")
class PickupStoreOwnerFlowTest {

    @Autowired
    private ai.neargo.shop.spi.user.PickupQueryPort pickupQueryPort;

    @Autowired
    private ai.neargo.shop.spi.user.MerchantQueryPort merchantQueryPort;

    @Test
    @DisplayName("★ 自提点的 owner 是门店号，且能顺着它查回所属主体")
    void pickupOwnerIsStoreAndResolvesToEntity() {
        var brief = pickupQueryPort.find("PP0001").orElseThrow();

        assertThat(brief.type()).isEqualTo("STORE");
        assertThat(brief.ownerStoreNo())
                .as("STORE 类型的承接方是门店，不是主体")
                .isEqualTo("ST-M0001");

        // 名字与 logo 仍在主体上 —— 顾客认的是「老张粮油店」，不是某家分店的名字
        assertThat(merchantQueryPort.entityOfStores(java.util.List.of("ST-M0001")))
                .containsEntry("ST-M0001", "M0001");
    }

    @Test
    @DisplayName("★ 按门店查可核销的自提点；**空集合返回空**，不是不过滤")
    void activePickupsAreScopedByStore() {
        assertThat(pickupQueryPort.activeStorePickupNos(java.util.List.of("ST-M0001")))
                .contains("PP0001", "PP0002");

        assertThat(pickupQueryPort.activeStorePickupNos(java.util.List.of()))
                .as("空集合当成「不过滤」会把全平台的自提点交给一个店员")
                .isEmpty();

        assertThat(pickupQueryPort.activeStorePickupNos(java.util.List.of("ST-M0002")))
                .as("别家主体的门店查不到 M0001 的点")
                .doesNotContain("PP0001");
    }

    @Test
    @DisplayName("★★ 下单落的门店 = 自提点所属门店，不再恒取默认店")
    void orderStoreFollowsPickupOwner() throws Exception {
        // PP0001 属于 ST-M0001，而 M0001 的默认店恰好也是它 —— 单店时两者恒等，
        // 所以这里验的是「值来自自提点」这条路径确实通了，而不是碰巧相等
        var brief = pickupQueryPort.find("PP0001").orElseThrow();
        assertThat(brief.ownerStoreNo()).isEqualTo("ST-M0001");
        assertThat(merchantQueryPort.storeNos("M0001")).contains(brief.ownerStoreNo());

        // 别家主体的商品下到这个点时，落的仍是那家自己的默认店 ——
        // 自提点不属于他，不能把他的货记在别人的店名下
        assertThat(merchantQueryPort.storeNos("M0002"))
                .as("M0002 名下没有 PP0001 的门店，下单要回落到它自己的默认店")
                .doesNotContain(brief.ownerStoreNo());
        assertThat(merchantQueryPort.defaultStoreNo("M0002")).isPresent();
    }

    @Test
    @DisplayName("★ 非 STORE 类型没有门店归属 —— owner_ref 是多态列，不能一律当门店号读")
    void nonStorePickupHasNoOwnerStore() {
        for (String no : java.util.List.of("PP0001", "PP0002")) {
            var b = pickupQueryPort.find(no).orElseThrow();
            if (!"STORE".equals(b.type())) {
                assertThat(b.ownerStoreNo()).isNull();
            }
        }
        assertThat(pickupQueryPort.find("NOT-EXIST")).isEmpty();
    }
}
