package ai.neargo.shop.invoice.api.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.neargo.common.data.scope.DataScopeSpec;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 运营会话 → 可见主体（TDD-发票模块 §18.7），与物流运单同口径。 */
class OpsEntityScopeTest {

    @Test
    void ac40MerchantDimensionBecomesEntityNosAndOtherDimensionsSeeNothing() {
        assertNull(OpsEntityScope.of(null), "没有数据域 = 不限");
        assertNull(OpsEntityScope.of(new DataScopeSpec(true, List.of())), "全部 = 不限");
        assertEquals(Set.of("E1", "E2"), OpsEntityScope.of(new DataScopeSpec(false, List.of(
                new DataScopeSpec.Rule("MERCHANT", Set.of("E1")), new DataScopeSpec.Rule("MERCHANT", Set.of("E2")),
                new DataScopeSpec.Rule("COMMUNITY", Set.of("C1"))))));
        assertEquals(Set.of(), OpsEntityScope.of(new DataScopeSpec(false, List.of(
                new DataScopeSpec.Rule("COMMUNITY", Set.of("C1"))))), "发票表上没有社区列 = 看不到");

        assertTrue(OpsEntityScope.allows(null, "E9"));
        assertTrue(OpsEntityScope.allows(Set.of("E1"), "E1"));
        assertFalse(OpsEntityScope.allows(Set.of("E1"), "E2"));
        assertFalse(OpsEntityScope.allows(Set.of(), "E1"));
    }
}
