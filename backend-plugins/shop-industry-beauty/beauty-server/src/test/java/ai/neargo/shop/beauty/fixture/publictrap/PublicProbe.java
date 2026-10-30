package ai.neargo.shop.beauty.fixture.publictrap;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 扩大到 beauty 全包扫描就会误注册本探针。 */
@RestController
public class PublicProbe {
    @GetMapping({"/mp/beauty-trap", "/biz/beauty-trap", "/ops/beauty-trap"})
    public String trap() {
        return "unexpected-public-route";
    }
}
