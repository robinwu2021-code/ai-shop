package ai.neargo.shop.product.api.biz;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.product.dto.GoodsVO;
import ai.neargo.shop.product.dto.SpecTemplateVO;
import ai.neargo.shop.product.service.MerchantGoodsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * B 端商品管理（[API 清单 §3.3]，B-11.3）。
 *
 * <p>作用域全部来自 {@link BizContext#requireMerchantNo()} —— <b>路径与入参里
 * 一律不接受 merchantNo</b>。接受了就等于把「我是谁」交给调用方声明，
 * 而那正是越权访问的入口。
 */
@Profile("api")
@RestController
public class BizGoodsController {

    private final MerchantGoodsService goodsService;

    public BizGoodsController(MerchantGoodsService goodsService) {
        this.goodsService = goodsService;
    }

    /**
     * 我的商品列表。
     *
     * @param status ON_SALE / OFF_SALE / AUDITING / REJECTED；空表示全部。
     *               <b>包含下架与被驳回的</b> —— 看不到被驳回的商品，店主就不知道要改什么
     */
    @GetMapping("/biz/goods")
    public PageData<GoodsVO> list(@RequestParam(required = false) String status,
                                  @RequestParam(defaultValue = "1") long page,
                                  @RequestParam(defaultValue = "20") long size) {
        return goodsService.list(BizContext.requireMerchantNo(), status, page, Math.min(size, 50));
    }

    @GetMapping("/biz/goods/{goodsNo}")
    public GoodsVO detail(@PathVariable String goodsNo) {
        return goodsService.detail(BizContext.requireMerchantNo(), goodsNo);
    }

    /** 新建 / 编辑。<b>保存后回到待审核并强制下架</b> —— 否则「改成别的东西再卖」能绕开审核。 */
    @PostMapping("/biz/goods/save")
    public GoodsVO save(@RequestBody SaveGoodsReq req) {
        return goodsService.save(BizContext.requireMerchantNo(), new MerchantGoodsService.SaveCommand(
                req.goodsNo(), req.title(), req.subtitle(),
                req.titleI18n(), req.subtitleI18n(), req.type(),
                req.cover(), req.images(),
                req.specGroups() == null ? List.of() : req.specGroups().stream()
                        .map(g -> new MerchantGoodsService.SpecGroup(
                                g.name(), g.options(), g.optionCodes(), g.templateNo()))
                        .toList(),
                req.skus() == null ? List.of() : req.skus().stream()
                        .map(s -> new MerchantGoodsService.Sku(
                                s.skuNo(), s.optionValues(), s.price(), s.priceByMarket(), s.stock()))
                        .toList()));
    }

    @PostMapping("/biz/goods/{goodsNo}/toggle")
    public GoodsVO toggle(@PathVariable String goodsNo, @RequestBody ToggleReq req) {
        return goodsService.toggle(BizContext.requireMerchantNo(), goodsNo,
                Boolean.TRUE.equals(req.onSale()));
    }

    /** 改库存。<b>不触发重审</b> —— 补货是每天都在做的事。 */
    @PostMapping("/biz/goods/{goodsNo}/stock")
    public GoodsVO stock(@PathVariable String goodsNo, @RequestBody StockReq req) {
        return goodsService.saveStock(BizContext.requireMerchantNo(), goodsNo,
                req.skuNo(), req.stock() == null ? 0 : req.stock());
    }

    @GetMapping("/biz/spec-templates")
    public List<SpecTemplateVO> specTemplates(@RequestParam(required = false) String categoryType) {
        return goodsService.specTemplates(BizContext.requireMerchantNo(), categoryType);
    }

    @PostMapping("/biz/spec-templates")
    public SpecTemplateVO saveSpecTemplate(@RequestBody SaveTemplateReq req) {
        return goodsService.saveSpecTemplate(BizContext.requireMerchantNo(), req.name(),
                req.options() == null ? List.of() : req.options().stream()
                        .map(o -> new MerchantGoodsService.SpecOption(o.code(), o.label()))
                        .toList());
    }

    /**
     * 拍照识别商品。
     *
     * <p><b>一期没有视觉模型，恒返回「没认出来」（confidence=0）。</b>
     *
     * <p>为什么保留这个接口而不是从契约里删掉：B 端拍照录商品是<b>已经做完的交互</b>，
     * 契约里写得很清楚「低于阈值时页面不预填，只提示没认出来」——
     * 也就是说这条路径本来就设计成识别失败也能继续手填。返回 0 置信度是这个设计里
     * <b>合法的一档</b>，前端会正确降级；而删掉接口会让那个页面直接 404。
     *
     * <p>接上模型时只改这一个方法。在那之前，这里不该假装认出了什么。
     */
    @PostMapping("/biz/goods/recognize")
    public GoodsGuessVO recognize(@RequestBody RecognizeReq req) {
        return new GoodsGuessVO("", "NORMAL", 0d);
    }

    public record SaveGoodsReq(String goodsNo, String title, String subtitle,
                               Map<String, String> titleI18n, Map<String, String> subtitleI18n,
                               String type, String cover, List<String> images,
                               List<SpecGroupReq> specGroups, List<SkuReq> skus) {
    }

    public record SpecGroupReq(String name, List<String> options, List<String> optionCodes,
                               String templateNo) {
    }

    public record SkuReq(String skuNo, List<String> optionValues, long price,
                         Map<String, Long> priceByMarket, int stock) {
    }

    public record ToggleReq(Boolean onSale) {
    }

    public record StockReq(String skuNo, Integer stock) {
    }

    public record SaveTemplateReq(String name, List<OptionReq> options) {
    }

    public record OptionReq(String code, String label) {
    }

    public record RecognizeReq(String imageUrl) {
    }

    /** 对齐 b-app {@code GoodsGuess}。全部是建议值，店主可改可弃。 */
    public record GoodsGuessVO(String title, String type, double confidence) {
    }
}
