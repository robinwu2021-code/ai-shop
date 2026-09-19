package ai.neargo.shop.portal.mp;

import ai.neargo.shop.common.PageData;
import ai.neargo.shop.product.dto.GoodsVO;
import ai.neargo.shop.product.service.GoodsFavoriteService;
import ai.neargo.shop.user.dto.StoreBriefVO;
import ai.neargo.shop.user.service.StoreFavoriteService;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 我的收藏：商品 / 店铺（TDD-C端商品收藏与送达判断，原型 g07 / g08）。全部要登录。
 *
 * <p>单开一个控制器而不是塞进 {@link MpCatalogController}：那个已经装着十来种资源，
 * 控制器内聚闸门的欠账只准变少。
 */
@Profile("api")
@RestController
public class MpFavoriteController {

    private static final long DEFAULT_SIZE = 20;
    private static final long MAX_SIZE = 50;

    private final GoodsFavoriteService goodsFavoriteService;
    private final StoreFavoriteService storeFavoriteService;

    public MpFavoriteController(GoodsFavoriteService goodsFavoriteService,
                                StoreFavoriteService storeFavoriteService) {
        this.goodsFavoriteService = goodsFavoriteService;
        this.storeFavoriteService = storeFavoriteService;
    }

    /** 收藏 / 取消一件商品。返回操作之后的状态 */
    @PostMapping("/mp/favorite/goods/{goodsNo}")
    public FavoriteState toggleGoods(@PathVariable String goodsNo) {
        return new FavoriteState(goodsFavoriteService.toggle(goodsNo));
    }

    /** 我的收藏 · 商品，收藏时间倒序；下架的也在（onSale=false，端上压淡） */
    @GetMapping("/mp/favorite/goods")
    public PageData<GoodsVO> goods(@RequestParam(defaultValue = "1") long page,
                                   @RequestParam(defaultValue = "" + DEFAULT_SIZE) long size) {
        return goodsFavoriteService.page(page, Math.min(size, MAX_SIZE));
    }

    /** 我的收藏 · 店铺：只有收藏，不混入归因店 */
    @GetMapping("/mp/favorite/store")
    public List<StoreBriefVO> stores() {
        return storeFavoriteService.favorites();
    }

    /** 收藏 / 取消一家店。返回操作之后的状态 */
    @PostMapping("/mp/favorite/store/{merchantNo}")
    public FavoriteState toggleStore(@PathVariable String merchantNo) {
        storeFavoriteService.toggle(merchantNo);
        return new FavoriteState(storeFavoriteService.isFavorited(merchantNo));
    }

    /** 收藏状态。单独一个形状而不是裸 boolean：全局信封会把裸值包成 data，端上要的就是这一个字段 */
    public record FavoriteState(boolean favorited) {
    }
}
