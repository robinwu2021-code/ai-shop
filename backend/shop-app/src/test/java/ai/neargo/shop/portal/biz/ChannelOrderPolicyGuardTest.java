package ai.neargo.shop.portal.biz;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.auth.BizPerms;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.reflect.Method;
import java.util.Arrays;

/** D4: duty managers pause / resume channel orders; limits and payment modes stay owner-only. */
class ChannelOrderPolicyGuardTest {
    private static final Class<?> C = BizChannelOrderPolicyController.class;

    private static Method method(String name) {
        return Arrays.stream(C.getDeclaredMethods())
                .filter(m -> m.getName().equals(name))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void toggleNeedsOnlyStoreWhileEverythingElseStaysOwnerOnly() {
        assertThat(C.getAnnotation(PreAuthorize.class).value()).contains("'" + BizPerms.STORE_ADMIN + "'");
        assertThat(method("setEnabled").getAnnotation(PreAuthorize.class).value())
                .isEqualTo("@perm.canBiz('" + BizPerms.STORE + "')");
        for (String owner : new String[] {"list", "get", "save"})
            assertThat(method(owner).getAnnotation(PreAuthorize.class))
                    .as(owner + " must inherit the owner-only class guard")
                    .isNull();
    }
}
