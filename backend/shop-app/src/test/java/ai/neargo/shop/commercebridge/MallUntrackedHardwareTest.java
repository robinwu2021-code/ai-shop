package ai.neargo.shop.commercebridge;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

import ai.neargo.commerce.api.catalog.*;
import ai.neargo.commerce.api.common.*;
import ai.neargo.commerce.api.port.*;
import ai.neargo.shop.auth.*;
import ai.neargo.shop.config.MallOperatorSessions;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.List;
import java.util.Optional;

/**
 * 商城不接进销存（默认 UNTRACKED）：商家能买套餐（PLAN）、硬件（HARDWARE）与二者组合的套装，
 * 硬件不因「没有库存映射」挡在下单前；显式配 coordinated 时仍按原规则 fail closed。
 */
class MallUntrackedHardwareTest {
    private static final StoreSnapshot STORE =
            new StoreSnapshot(
                    new StoreRef("MAIN", "S"),
                    "merchant",
                    "RETAIL",
                    StoreStatus.ACTIVE,
                    CommerceModel.STORE_V1);

    private static SkuComponent plan() {
        return new SkuComponent(
                "plan", ComponentKind.PLAN, "plan-v1", "POS_PRO", null, 365, 1, 1, false, "IMMEDIATE");
    }

    private static SkuComponent hardware() {
        return new SkuComponent(
                "device", ComponentKind.HARDWARE, null, null, "HW-POS-1", null, 1, 1, false, null);
    }

    private static SkuDraft sku(String no, List<SkuComponent> components) {
        return new SkuDraft(
                "product",
                no,
                no + "-v1",
                no,
                new Money("TWD", 69900),
                new Money("TWD", 69900),
                List.of("RETAIL"),
                "refund-policy",
                components,
                1,
                0,
                "test");
    }

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withUserConfiguration(ShopMallConfiguration.class)
                .withPropertyValues(
                        "spring.profiles.active=api",
                        "commerce.enabled=true",
                        "commerce.catalog.enabled=true",
                        "commerce.mall.enabled=true",
                        "commerce.mall.http.enabled=true",
                        "commerce.mall.payment.enabled=false",
                        "commerce.mall.refund.enabled=false")
                .withBean(
                        ai.neargo.commerce.api.foundation.FoundationAuthorityPort.class,
                        () -> mock(ai.neargo.commerce.api.foundation.FoundationAuthorityPort.class))
                .withBean(
                        ai.neargo.commerce.api.mall.StorefrontFacade.class,
                        () -> mock(ai.neargo.commerce.api.mall.StorefrontFacade.class))
                .withBean(AccountSessionVerifier.class, () -> mock(AccountSessionVerifier.class))
                .withBean(
                        MallOperatorSessions.class,
                        () -> new MallOperatorSessions(mock(AccountSessionVerifier.class)))
                .withBean(BizIdentityResolver.class, () -> mock(BizIdentityResolver.class))
                .withBean(
                        ShopCommerceStoreAdapter.class, () -> mock(ShopCommerceStoreAdapter.class));
    }

    @Test
    void byDefaultPlanHardwareAndBundleSkusAreNotBlockedByInventory() {
        runner().run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            var readiness = context.getBean(CheckoutReadinessPort.class);
                            for (var skus :
                                    List.of(
                                            List.of(sku("plan", List.of(plan()))),
                                            List.of(sku("hardware", List.of(hardware()))),
                                            List.of(sku("bundle", List.of(plan(), hardware()))))) {
                                assertThat(
                                                readiness
                                                        .evaluate(
                                                                STORE,
                                                                new Money("TWD", 69900),
                                                                skus)
                                                        .reasons())
                                        .doesNotContain("INVENTORY_MAPPING_UNAVAILABLE");
                            }
                            assertThat(context.getBean(ShopMallHardwareInventoryAdapter.class)
                                            .mapped("HW-POS-1"))
                                    .isTrue();
                        });
    }

    @Test
    void coordinatedModeStillFailsClosedWithoutInventory() {
        runner().withPropertyValues("shop.commerce.mall.hardware-stock=coordinated")
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            var reasons =
                                    context.getBean(CheckoutReadinessPort.class)
                                            .evaluate(
                                                    STORE,
                                                    new Money("TWD", 69900),
                                                    List.of(
                                                            sku(
                                                                    "bundle",
                                                                    List.of(plan(), hardware()))))
                                            .reasons();
                            assertThat(reasons).contains("INVENTORY_MAPPING_UNAVAILABLE");
                            // 只有 PLAN 的套餐不受库存模式影响
                            assertThat(
                                            context.getBean(CheckoutReadinessPort.class)
                                                    .evaluate(
                                                            STORE,
                                                            new Money("TWD", 69900),
                                                            List.of(sku("plan", List.of(plan()))))
                                                    .reasons())
                                    .doesNotContain("INVENTORY_MAPPING_UNAVAILABLE");
                        });
    }

    /** 与 CommerceFulfillmentService#validateInventory 对 RESERVE / SHIP / RELEASE 成功证据的要求一致。 */
    @Test
    void untrackedPortAnswersEveryCommandIdempotentlyWithExactOperationEvidence() {
        var port = ShopMallHardwareInventoryAdapter.untracked();
        var reserve = new InventoryCommand("MAIN", "order", "component", "HW-POS-1", 3, "reserve:1");
        var reserved = port.reserve(reserve);
        assertThat(reserved.status()).isEqualTo(AsyncStatus.SUCCEEDED);
        assertThat(reserved.operationNo()).isEqualTo("reserve:1");
        assertThat(reserved.operationQuantity()).isEqualTo(3);
        assertThat(port.reserve(reserve)).isEqualTo(reserved);

        // 不留账：查询回 NOT_FOUND（commerce 会重放原命令），从不冒充已释放
        var query = port.query(new InventoryQuery("MAIN", "component", "reserve:1"));
        assertThat(query.status()).isEqualTo(AsyncStatus.UNKNOWN);
        assertThat(query.errorCode()).isEqualTo(Optional.of("NOT_FOUND"));
        assertThat(query.operationNo()).isEqualTo("reserve:1");

        var ship =
                new InventoryCommand(
                        "MAIN", "order", "component", "HW-POS-1", 2, "ship:1", "SF", "T1", "ops");
        var shipped = port.ship(ship);
        assertThat(shipped.status()).isEqualTo(AsyncStatus.SUCCEEDED);
        assertThat(shipped.operationNo()).isEqualTo("ship:1");
        assertThat(shipped.operationQuantity()).isEqualTo(2);

        var released = port.release(new InventoryQuery("MAIN", "component", "reserve:1"));
        assertThat(released.status()).isEqualTo(AsyncStatus.SUCCEEDED);
        assertThat(released.operationNo()).isEqualTo("reserve:1");
        assertThat(released.reservedQuantity()).isZero();
        assertThat(released.shippedQuantity()).isZero();
        assertThat(released.operationQuantity()).isZero();
    }
}
