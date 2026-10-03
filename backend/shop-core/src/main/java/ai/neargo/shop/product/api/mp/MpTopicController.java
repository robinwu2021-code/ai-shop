package ai.neargo.shop.product.api.mp;

import ai.neargo.shop.common.PageData;
import ai.neargo.shop.product.dto.GoodsVO;
import ai.neargo.shop.product.service.TopicService;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 主题分类（陈列）—— 买家侧（批 E）。
 *
 * <p><b>游客可见</b>：首页的专题入口在登录之前就要看得见，与类目树同一条理由 ——
 * 逼人先登录才看得到「今天摆了什么」，会把一大半人挡在门外。
 */
@Profile("api")
@RestController
public class MpTopicController {

    private final TopicService service;

    public MpTopicController(TopicService service) {
        this.service = service;
    }

    /** 在架专题。**归档的不下发** —— 买家侧看到一个已经结束的专题只会是空位 */
    @GetMapping("/mp/topics")
    public List<TopicService.TopicVO> list() {
        return service.list(false);
    }

    /**
     * 专题里的商品。
     *
     * <p>专题已归档时这里仍然返回内容：分享出去的海报、历史链接都还指着它，
     * 让那些入口进来看到一页货，比看到一个 404 更接近「这个专题结束了」这件事本身。
     */
    @GetMapping("/mp/topics/{topicNo}/goods")
    public PageData<GoodsVO> goods(@PathVariable String topicNo,
                                   @RequestParam(defaultValue = "1") long page,
                                   @RequestParam(defaultValue = "20") long size) {
        return service.goods(topicNo, page, Math.min(size, 50));
    }
}
