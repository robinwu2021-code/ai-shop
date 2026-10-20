package ai.neargo.shop.food.server.fixture;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** 测试专用内部入口；依靠真实 FoodApplication 的包扫描发现。 */
@RestController
public class InternalProbe {
    @GetMapping("/internal/food/test-probe")
    public ProbeVO probe() {
        return new ProbeVO("ok");
    }

    @PostMapping("/internal/food/test-write-probe")
    public ProbeVO writeProbe() {
        return new ProbeVO("ok");
    }

    public record ProbeVO(String result) {
    }
}
