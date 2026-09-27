package ai.neargo.shop.portal.mp;

import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.content.ContentService;
import ai.neargo.shop.content.ContentService.QuestionVO;
import ai.neargo.shop.spi.product.GoodsQueryPort;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 商品问答：「大家还问」与「我要问」（TDD-C 端商品详情页·内容丰富度 §3.3）。
 *
 * <p><b>放在 portal 而不是 content 包里</b>：提问要给运营端留一条按规格看的线索
 * （{@code sku_no} + 当时的标题快照），而那是<b>商品域的知识</b> ——
 * content 域只有一张 {@code cnt_question}，它不认识商品。跨域组合放 portal 是架构规则第 1 条。
 *
 * <p><b>这两条端点此前不存在</b>，而模型、运营端的回答界面、审核流程都早就齐了：
 * {@code cnt_question} 建表在 V61，运营端 {@code /ops/contents/questions} 能列能答，
 * 只是<b>买家没有任何入口把问题提进来</b> —— 那一屏因此永远是空的。
 */
@Profile("api")
@RestController
public class MpQuestionController {

    /** 详情页只摆 3 条，「查看全部」时端上自己要更多 */
    private static final int DEFAULT_LIMIT = 3;

    private final ContentService contentService;
    private final GoodsQueryPort goodsPort;

    public MpQuestionController(ContentService contentService, GoodsQueryPort goodsPort) {
        this.contentService = contentService;
        this.goodsPort = goodsPort;
    }

    /**
     * 某件商品下**已回答**的问答。游客可见 —— 与评价同一条理由：看得到才有下单动机。
     *
     * <p>没回答的不下发：一排没人答的问题传达的是「这家店不管事」，比没有问答区更糟。
     *
     * <p><b>商品号走路径而不是查询参数</b>：它是这条集合的归属，不是筛选条件
     * （与 {@code GET /mp/goods/&#123;goodsNo&#125;/batch} 同一形状）。
     * 顺带也让端点判权那套探测判得出来 —— 它按路径里的占位符填种子号，
     * 而查询参数它不填，于是那条端点永远只能探到一个 400。
     */
    @GetMapping("/mp/goods/{goodsNo}/question")
    public List<BuyerQuestionVO> list(@org.springframework.web.bind.annotation.PathVariable String goodsNo,
                                      @RequestParam(defaultValue = "" + DEFAULT_LIMIT) int limit) {
        return contentService.answeredOfGoods(goodsNo, limit).stream().map(BuyerQuestionVO::of).toList();
    }

    /**
     * 提问。落库即待回答，由运营在后台答。
     *
     * <p>顺手带上这件商品的 SKU 快照（号与当时的标题）：运营端那一屏按规格看，
     * 而商品改名或换规格之后，那条问题说的仍该是当时那件货。
     * 取不到快照不拦 —— 问题本身比那条线索重要。
     */
    @PostMapping("/mp/question")
    public BuyerQuestionVO ask(@RequestBody @Valid AskReq req) {
        /*
         * **让 currentUserNo 自己抛**，不在这里补一层 BizException：
         * 它抛出的是认证异常，由 ApiAuthEntryPoint 翻成真正的 HTTP 401；
         * 而 BizException 会被统一信封裹成 200 + code=10401 ——
         * 判权那套探测按 HTTP 状态判，于是「要登录」会被看成「没拦住」。
         */
        String userNo = SecurityUtils.currentUserNo();
        var snap = goodsPort.snapshotOfGoods(req.goodsNo());
        return BuyerQuestionVO.of(contentService.ask(req.goodsNo(),
                snap.map(GoodsQueryPort.SkuSnapshot::skuNo).orElse(null),
                snap.map(GoodsQueryPort.SkuSnapshot::title).orElse(null),
                req.content(), userNo));
    }

    /**
     * 买家看得到的那几项。
     *
     * <p><b>不直接下发 {@link QuestionVO}</b>：那是运营端那一屏的形状，带着
     * {@code askedBy}（问的是谁）、{@code answeredBy}（哪个运营答的）、
     * {@code hideReason}（为什么被藏）。前一个是别人的身份，后两个是内部信息 ——
     * 没有一项是买家该看到的，而端上不声明它们并不会让它们不被发出去。
     */
    public record BuyerQuestionVO(String questionNo, String goodsNo, String skuNo, String skuTitle,
                                  String content, String answer, Long answeredAt,
                                  String status, String createdAt) {

        static BuyerQuestionVO of(QuestionVO q) {
            return new BuyerQuestionVO(q.questionNo(), q.goodsNo(), q.skuNo(), q.skuTitle(),
                    q.content(), q.answer(), q.answeredAt(), q.status(), q.createdAt());
        }
    }

    /** @param content 问题正文。空的问题对谁都没用，在入口就挡住 */
    public record AskReq(@NotBlank String goodsNo, @NotBlank String content) {
    }
}
