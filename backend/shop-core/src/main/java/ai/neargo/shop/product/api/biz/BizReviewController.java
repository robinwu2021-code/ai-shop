package ai.neargo.shop.product.api.biz;

import ai.neargo.shop.auth.BizPerms;
import org.springframework.security.access.prepost.PreAuthorize;
import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.product.review.ReviewService;
import ai.neargo.shop.product.review.dto.ReviewVO;
import org.springframework.context.annotation.Profile;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 商家端 · 评价（B-11.7）。
 *
 * <p>此前只有 C 端的读接口 —— 商家能看到差评，却无法回复也无法申诉，
 * 唯一的出路是打客服。对一家店来说，一条无法回应的差评就是永久的。
 */
@Profile("api")
@RestController
@Validated
public class BizReviewController {

    private final ReviewService reviewService;

    public BizReviewController(ReviewService reviewService) {
        this.reviewService = reviewService;
    }

    /**
     * 本店的评价。
     *
     * <p>按**主体**取而不是按门店：评价挂在商品与订单上，
     * 而门店维度的评价归属还没有数据来源（{@code rvw_review} 上没有 store_no）。
     * 按门店切会切出一堆空列表，那比不切更让人困惑。
     */
    @PreAuthorize("@perm.canBiz('" + BizPerms.REVIEW + "')")
    @GetMapping("/biz/review")
    public List<ReviewVO> list() {
        return reviewService.list(null, BizContext.requireMerchantNo());
    }

    @PreAuthorize("@perm.canBiz('" + BizPerms.REVIEW + "')")
    @PostMapping("/biz/review/{reviewNo}/reply")
    public ReviewVO reply(@PathVariable String reviewNo, @RequestBody ReplyReq req) {
        return reviewService.reply(BizContext.requireMerchantNo(), reviewNo, req.reply());
    }

    /** 申诉差评。只有低分可申诉，且一条只能申诉一次 —— 校验在 Service。 */
    @PreAuthorize("@perm.canBiz('" + BizPerms.REVIEW + "')")
    @PostMapping("/biz/review/{reviewNo}/appeal")
    public ReviewVO appeal(@PathVariable String reviewNo, @RequestBody AppealReq req) {
        return reviewService.appeal(BizContext.requireMerchantNo(), reviewNo,
                req.reason(), req.images());
    }

    public record ReplyReq(String reply) {
    }

    /** @param images 举证图：聊天记录、物流截图 */
    public record AppealReq(String reason, List<String> images) {
    }
}
