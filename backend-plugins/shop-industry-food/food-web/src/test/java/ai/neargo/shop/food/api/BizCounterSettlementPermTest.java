package ai.neargo.shop.food.api;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.food.api.biz.BizCounterSettlementController;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

/** 规则 R6：柜台结账的写操作要「收现金」权限，读只要「查看订单」；宿主的 BizEndpointPermTest 扫不到插件端点。 */
class BizCounterSettlementPermTest {
    @Test
    void writesNeedCashReceiveAndReadNeedsOrderView() {
        Map<String, String> actual = new TreeMap<>();
        for (Method m : BizCounterSettlementController.class.getDeclaredMethods()) {
            String path = m.isAnnotationPresent(PostMapping.class) ? "POST " + m.getAnnotation(PostMapping.class).value()[0]
                    : m.isAnnotationPresent(GetMapping.class) ? "GET " + m.getAnnotation(GetMapping.class).value()[0] : null;
            if (path == null) continue;
            PreAuthorize rule = m.getAnnotation(PreAuthorize.class);
            actual.put(path, rule == null ? "<none>" : rule.value());
        }
        String cash = "@perm.canBiz('" + BizPerms.CASH_RECEIVE + "')";
        assertThat(actual).containsExactlyInAnyOrderEntriesOf(Map.of(
                "POST /biz/x/food/checks/{checkNo}/counter-settlements", cash,
                "POST /biz/x/food/counter-settlements/{settlementNo}:finalize", cash,
                "POST /biz/x/food/counter-settlements/{settlementNo}:cancel", cash,
                "GET /biz/x/food/counter-settlements/{settlementNo}", "@perm.canBiz('" + BizPerms.ORDER_VIEW + "')"));
        assertThat(BizPerms.CASH_RECEIVE).isEqualTo("pay:cash:receive");
        assertThat(BizPerms.ORDER_VIEW).isEqualTo("biz:order:view");
        assertThat(Arrays.stream(BizCounterSettlementController.class.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(PostMapping.class) || m.isAnnotationPresent(GetMapping.class)))
                .hasSize(4);
    }
}
