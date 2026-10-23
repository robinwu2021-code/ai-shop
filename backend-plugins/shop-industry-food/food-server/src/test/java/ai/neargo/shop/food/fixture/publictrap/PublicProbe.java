package ai.neargo.shop.food.fixture.publictrap;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 扩大到 food 全包扫描就会误注册本探针。 */
@RestController
public class PublicProbe {
    @GetMapping({"/mp/food-trap", "/biz/food-trap", "/ops/food-trap"})
    public String trap() {
        return "unexpected-public-route";
    }
}
