package ai.neargo.inventory.guard;

import ai.neargo.inventory.api.InventoryError;
import ai.neargo.inventory.api.InventoryException;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** AC-28：凭证所属系统不在本套放行范围 → 拒绝。 */
class InstanceGuardTest {

    private static InstanceGuard guard(String code, String... systems) {
        InstanceProperties p = new InstanceProperties();
        p.setCode(code);
        p.setAllowedSystems(Set.of(systems));
        return new InstanceGuard(p);
    }

    @Test
    void deviceCredentialHittingShopInstanceIsRejected() {
        assertThatThrownBy(() -> guard("SHOP", "SHOP").check(new InventoryPrincipal("SHAREHUB", "O1", "STAFF:E1")))
                .isInstanceOf(InventoryException.class)
                .satisfies(e -> assertThat(((InventoryException) e).error()).isEqualTo(InventoryError.SYSTEM_NOT_SERVED));
    }

    @Test
    void shopCredentialHittingDeviceInstanceIsRejected() {
        assertThatThrownBy(() -> guard("DEVICE", "SHAREHUB", "CHARGING")
                .check(new InventoryPrincipal("SHOP", "O1", "SYSTEM:job")))
                .isInstanceOf(InventoryException.class);
    }

    @Test
    void sharedTestInstanceServesEverySystem() {
        InstanceGuard test = guard("TEST", "shop", " sharehub ", "CHARGING");
        for (String system : new String[] {"SHOP", "SHAREHUB", "CHARGING"}) {
            assertThatCode(() -> test.check(new InventoryPrincipal(system, "O1", null))).doesNotThrowAnyException();
        }
    }

    @Test
    void emptyAllowListServesNobody() {
        assertThatThrownBy(() -> guard("DEV").check(new InventoryPrincipal("SHOP", "O1", null)))
                .isInstanceOf(InventoryException.class);
    }
}
