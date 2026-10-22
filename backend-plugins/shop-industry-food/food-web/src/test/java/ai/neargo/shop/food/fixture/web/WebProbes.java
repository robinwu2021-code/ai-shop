package ai.neargo.shop.food.fixture.web;

import ai.neargo.shop.food.config.ConditionalOnFoodWebEnabled;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 只用于核验扫描和路由，不是营业 API。 */
public final class WebProbes {
    private WebProbes() {
    }

    @RestController
    @Profile("api")
    @ConditionalOnFoodWebEnabled
    public static class MpProbe {
        @GetMapping("/mp/food-test-probe")
        public String get() {
            return "food-mp";
        }
    }

    @RestController
    @Profile("api")
    @ConditionalOnFoodWebEnabled
    public static class BizProbe {
        @GetMapping("/biz/food-test-probe")
        public String get() {
            return "food-biz";
        }
    }

    @RestController
    @Profile("ops")
    @ConditionalOnFoodWebEnabled
    public static class OpsProbe {
        @GetMapping("/ops/food-test-probe")
        public String get() {
            return "food-ops";
        }
    }

    @RestController
    public static class RetailProbe {
        @GetMapping("/mp/retail-test-probe")
        public String get() {
            return "retail";
        }
    }
}
