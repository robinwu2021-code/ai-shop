package ai.neargo.shop.product.api.ops;

import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.product.dto.GoodsVO;
import ai.neargo.shop.product.service.TopicService;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 平台主题分类（陈列，批 E）。
 *
 * <p><b>主题不属于任何一家商家</b>，与类目、标准品同类 —— 所以这几条不接数据域。
 *
 * <p>⚠️ profile 必须是 {@code ops}：S8 部署隔离，标错的症状是这几条在运营端实例上
 * 根本不注册（404），而单测看不出来 —— 测试上下文里两个 profile 都在。
 */
@Profile("ops")
@RestController
public class OpsTopicController {

    private final TopicService service;

    public OpsTopicController(TopicService service) {
        this.service = service;
    }

    /**
     * 主题列表。<b>默认带上归档的</b> —— 运营看不见归档的话，
     * 「上周那个专题去哪了」就没有答案，他会再建一个同名的。
     */
    @GetMapping("/ops/topics")
    @PreAuthorize("@perm.can('" + Perms.PRODUCT_TOPIC_READ + "')")
    public List<TopicService.TopicVO> list(
            @RequestParam(defaultValue = "true") boolean includeArchived) {
        return service.list(includeArchived);
    }

    /** 新建或改。{@code topicNo} 为空 = 新建 */
    @PostMapping("/ops/topics")
    @PreAuthorize("@perm.can('" + Perms.PRODUCT_TOPIC_UPDATE + "')")
    public TopicService.TopicVO save(@RequestBody SaveReq req) {
        return service.save(new TopicService.SaveCommand(req.topicNo(), req.title(),
                req.subtitle(), req.cover(), req.sort(), req.startAt(), req.endAt()));
    }

    /**
     * 归档 / 取消归档。<b>没有删除</b> —— C 端历史链接与分享出去的海报都还指着它，
     * 删掉之后那些入口进来是 404，而它本来只需要「这个专题结束了」。
     */
    @PostMapping("/ops/topics/{topicNo}/archived")
    @PreAuthorize("@perm.can('" + Perms.PRODUCT_TOPIC_UPDATE + "')")
    public TopicService.TopicVO setArchived(@PathVariable String topicNo,
                                            @RequestBody ArchiveReq req) {
        return service.setArchived(topicNo, Boolean.TRUE.equals(req.archived()));
    }

    @GetMapping("/ops/topics/{topicNo}/goods")
    @PreAuthorize("@perm.can('" + Perms.PRODUCT_TOPIC_READ + "')")
    public PageData<GoodsVO> goods(@PathVariable String topicNo,
                                   @RequestParam(defaultValue = "1") long page,
                                   @RequestParam(defaultValue = "20") long size) {
        return service.goods(topicNo, page, Math.min(size, 100));
    }

    /**
     * 整份替换专题里的商品，顺序即传入顺序。
     *
     * <p><b>只收在架商品</b>：摆一件下架/待审的货进来，C 端点进去是空位，
     * 而运营在后台看到它明明在列表里。
     */
    @PostMapping("/ops/topics/{topicNo}/goods")
    @PreAuthorize("@perm.can('" + Perms.PRODUCT_TOPIC_UPDATE + "')")
    public PageData<GoodsVO> setGoods(@PathVariable String topicNo, @RequestBody GoodsReq req) {
        service.setGoods(topicNo, req.goodsNos());
        return service.goods(topicNo, 1, 100);
    }

    /** @param sort 首页排序，小的在前。不传 = 不改（新建时为 0） */
    public record SaveReq(String topicNo, String title, String subtitle, String cover,
                          Integer sort, Long startAt, Long endAt) {
    }

    public record ArchiveReq(Boolean archived) {
    }

    /** @param goodsNos 顺序即专题内的展示顺序 */
    public record GoodsReq(List<String> goodsNos) {
    }
}
