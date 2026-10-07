package ai.neargo.shop.product.port;

import ai.neargo.shop.product.service.MerchantGoodsService;
import ai.neargo.shop.spi.product.StoreShelfPort;
import org.springframework.stereotype.Component;

/**
 * 门店货架平台开关的落地：薄转调 {@link MerchantGoodsService}。
 * 压/放货架要走 setStoreOnSale → 总闸重算 → syncPool 那条**已有**链路，
 * 逻辑都在商品服务里 —— Port 只负责让 merchant 域够得着。
 */
@Component
public class StoreShelfPortImpl implements StoreShelfPort {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(StoreShelfPortImpl.class);

    private final MerchantGoodsService goodsService;

    public StoreShelfPortImpl(MerchantGoodsService goodsService) {
        this.goodsService = goodsService;
    }

    @Override
    public void platformOffline(String entityNo, String storeNo) {
        goodsService.platformOfflineStore(entityNo, storeNo);
    }

    @Override
    public void platformRestore(String entityNo, String storeNo) {
        goodsService.platformRestoreStore(entityNo, storeNo);
    }

    /**
     * 重建放到**后台单线程**跑，调用方不等它（2026-10-07）。
     *
     * <p>这一步要把「这家店能送到哪些小区」×「主体名下每件货」整个重算一遍。
     * 深圳的小区导进来之后，生产上是 16 件货 × 23656 个开放小区 ——
     * 实测保存一次送货方式 <b>24~27 秒</b>（同一个接口的读只要 0.3 秒），
     * 而商家按下开关后界面要等它返回才动，报障原话是「切换商家配送，反应很慢」。
     *
     * <p>语义上本来就允许晚一步：接口注释写着「失败不阻塞，可见性晚一步，
     * 下次上下架会自愈」。既然失败都能接受，慢一步更能接受。
     *
     * <p><b>单线程 + 有界队列 + 同一主体合并</b>（照 PlaceResolver 的后台刷新那套，
     * 本工程没开 {@code @EnableAsync}）：商家连点两下开关，排一次就够 ——
     * 第二次重建读到的是同样的库，做的是同样的事。队列满了就丢，丢掉的那次
     * 下一次上下架会自愈，而队列涨起来会把整个服务拖下水。
     */
    private final java.util.Set<String> queued = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private final java.util.concurrent.ThreadPoolExecutor pool =
            new java.util.concurrent.ThreadPoolExecutor(
                    1, 1, 0L, java.util.concurrent.TimeUnit.MILLISECONDS,
                    new java.util.concurrent.ArrayBlockingQueue<>(64),
                    r -> {
                        Thread t = new Thread(r, "community-pool-resync");
                        t.setDaemon(true);
                        return t;
                    },
                    new java.util.concurrent.ThreadPoolExecutor.DiscardPolicy());

    @Override
    public void resyncPools(String entityNo) {
        if (entityNo == null || entityNo.isBlank() || !queued.add(entityNo)) {
            // 已经排着一次了：那一次读的是同一个库，做的是同一件事
            return;
        }
        pool.execute(() -> {
            queued.remove(entityNo);
            long t0 = System.currentTimeMillis();
            /*
             * **吞掉异常**，见接口注释：审核通过与范围保存都已经成功了，
             * 让池重建把它们回滚掉是更坏的结果 —— 商家会看到「审核失败」，
             * 而审核其实过了。可见性晚一步，下次上下架会自愈。
             */
            try {
                int n = goodsService.resyncCommunityPools(entityNo);
                log.info("[pool] 主体 {} 可达范围变化，重建社区池：{} 件商品，用时 {} ms",
                        entityNo, n, System.currentTimeMillis() - t0);
            } catch (RuntimeException e) {
                log.error("[pool] 主体 {} 社区池重建失败 —— 这批货可能仍对买家不可见，"
                        + "商家任意一次上下架会自愈", entityNo, e);
            }
        });
    }
}
