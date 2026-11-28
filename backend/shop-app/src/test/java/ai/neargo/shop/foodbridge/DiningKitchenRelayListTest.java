package ai.neargo.shop.foodbridge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;

import java.util.Arrays;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 餐饮后厨事件切到中间件（ADR-067 第 4 步，TDD-餐饮域迁移新库 §2.8 D-4）：厨房单、事件单据的打印是必达的，
 * 五个事件整组改由“发件箱 → 中间件”投递，各消费者按自己的保序订阅收。
 */
@DisplayName("餐饮后厨事件经中间件投递")
class DiningKitchenRelayListTest {

    @Test
    @DisplayName("默认名单整组包含餐饮后厨的五个事件")
    void defaultRelayListContainsDiningKitchenEvents() throws Exception {
        var sources = new YamlPropertySourceLoader().load("app", new ClassPathResource("application.yml"));
        String raw = String.valueOf(sources.getFirst().getProperty("shop.events.outbox-event-types"));
        String defaults = raw.substring(raw.indexOf(':') + 1, raw.lastIndexOf('}'));
        assertThat(Arrays.stream(defaults.split(",")).map(String::trim).collect(Collectors.toSet()))
                .contains("DINING_ORDER_ACCEPTED", "DINING_SERVICE_EVENT", "DINING_CUSTODY_HANDOVER",
                        "FOOD_CHECK_CHANGED", "DINING_ORDER_CHANGED");
    }
}
