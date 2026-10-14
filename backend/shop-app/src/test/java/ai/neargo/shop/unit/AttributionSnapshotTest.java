package ai.neargo.shop.unit;

import ai.neargo.shop.trade.entity.OrdSubOrder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 佣金归属快照必须**存在于子订单上**，而不是结算时现推。
 *
 * <p>这条用「字段在不在」来守，看着弱，但它防的是一个很具体的回退：
 * 有人为了「减少冗余」把这两列删掉，改成结算时按 {@code pickup_no} 现查 ——
 * 那一刻代码更简洁、所有测试照样绿，而**自提点一旦换承接门店，
 * 历史订单的归属就跟着变了**：上个月的单算到新承接方头上，
 * 钱却早已结给旧的。这种错不会报错，只会在对账时变成一笔说不清的差额。
 *
 * <p>真正的行为验证要等分佣逻辑落地（现在 commission_minor 还只是个存的金额），
 * 那时这条用例应当被一条真链路用例取代 —— 在那之前它占住位置。
 */
class AttributionSnapshotTest {

    private static boolean has(String name) {
        return Arrays.stream(OrdSubOrder.class.getDeclaredFields())
                .map(Field::getName)
                .anyMatch(name::equals);
    }

    @Test
    @DisplayName("★★★ 子订单要带下单时的承接方 —— 换了承接门店不许改写历史归属")
    void subOrderCarriesAttributionSnapshot() {
        assertThat(has("pickupOwnerRef"))
                .as("没有它，「这一单算谁的」只能结算时现推，而那是会变的")
                .isTrue();
        assertThat(has("pickupOwnerStoreNo"))
                .as("STORE 类型自提点的承接门店，与 ownerRef 成对")
                .isTrue();
    }

    @Test
    @DisplayName("★★ 与它同族的快照都还在 —— 一起删掉是最可能的「精简」方式")
    void siblingSnapshotsStillThere() {
        // 这几列护的是同一件事的不同面：显示、联系人、钱
        for (String f : new String[]{"pickupName", "receiverName", "receiverPhone", "receiverAddress"}) {
            assertThat(has(f)).as("%s 是历史订单的快照，不该被「去冗余」掉", f).isTrue();
        }
    }
}
