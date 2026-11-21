package ai.neargo.shop.portal.mp;

import ai.neargo.shop.promotion.service.PeriodService;
import ai.neargo.shop.spi.marketing.PeriodPort.BatchView;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * C 端商品详情的社区集单块（原型 s26）：几点截单、哪天到哪取、这一期已订多少。
 *
 * <p>单独一个端点而不是塞进 {@code GoodsVO}：那个 record 在列表、详情、购物车三处构造，
 * 多一个要查库的块会让列表变成 N+1。只有详情页问它。
 *
 * <p>匿名可访问（与商品详情同）：未登录的人也要看得到截单时间才会下单。
 */
@Profile("api")
@RestController
public class MpBatchController {

    private final PeriodService periodService;

    public MpBatchController(PeriodService periodService) {
        this.periodService = periodService;
    }

    /** 不是集单商品时 data 为 null */
    @GetMapping("/mp/goods/{goodsNo}/batch")
    public BatchView batch(@PathVariable String goodsNo) {
        return periodService.batchOfGoods(goodsNo).orElse(null);
    }
}
