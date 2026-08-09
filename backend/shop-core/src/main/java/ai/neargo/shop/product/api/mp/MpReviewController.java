package ai.neargo.shop.product.api.mp;

import ai.neargo.shop.product.review.ReviewService;
import ai.neargo.shop.product.review.dto.ReviewVO;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 评价（C-RV-01~03）。 */
@RestController
public class MpReviewController {

    private final ReviewService reviewService;

    public MpReviewController(ReviewService reviewService) {
        this.reviewService = reviewService;
    }

    /** 评价列表。**游客可见** —— 看得到评价才有下单动机，与领券中心同一条理由。 */
    @GetMapping("/mp/review")
    public List<ReviewVO> list(@RequestParam(required = false) String goodsNo,
                               @RequestParam(required = false) String merchantNo) {
        return reviewService.list(goodsNo, merchantNo);
    }

    @PostMapping("/mp/review")
    public ReviewVO create(@RequestBody CreateReviewReq req) {
        return reviewService.create(new ReviewService.CreateCommand(
                req.orderNo(), req.goodsNo(), req.rating(), req.content(), req.images(),
                req.scores() == null ? null : new ReviewService.Scores(
                        req.scores().goods(), req.scores().fulfillment(), req.scores().service())));
    }

    @PostMapping("/mp/review/{reviewNo}/like")
    public ReviewVO toggleLike(@PathVariable String reviewNo) {
        return reviewService.toggleLike(reviewNo);
    }

    /**
     * @param scores 三维分。**可选** —— 老客户端不传，只给总分；
     *               强制必填会让存量版本的评价全部失败
     */
    public record CreateReviewReq(@NotBlank String orderNo, @NotBlank String goodsNo,
                                  int rating, String content, List<String> images,
                                  ScoresReq scores) {
    }

    public record ScoresReq(Integer goods, Integer fulfillment, Integer service) {
    }
}
