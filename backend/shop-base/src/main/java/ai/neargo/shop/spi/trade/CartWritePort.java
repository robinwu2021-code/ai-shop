package ai.neargo.shop.spi.trade;

/**
 * product → trade：一键再来一单要往购物车里加东西（C-ST-03）。
 *
 * <p>只暴露 {@code add}：门店主页没有理由删别人的购物车行，
 * Port 给多少能力，调用方就能做多少事。
 */
public interface CartWritePort {

    void add(String userNo, String goodsNo, String skuNo, int qty);
}
