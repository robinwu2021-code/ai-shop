package ai.neargo.shop.beauty.server.fixture;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** 测试专用内部入口；依靠真实 BeautyApplication 的包扫描发现。 */
@RestController
public class InternalProbe {
    @GetMapping("/internal/beauty/test-probe")
    public ProbeVO probe() {
        return new ProbeVO("ok");
    }

    @PostMapping("/internal/beauty/test-write-probe")
    public ProbeVO writeProbe() {
        return new ProbeVO("ok");
    }

    public record ProbeVO(String result) {
    }
}
