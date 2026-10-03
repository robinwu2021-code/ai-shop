package ai.neargo.shop.config;

import ai.neargo.shop.community.service.CommunityAdminService;
import ai.neargo.shop.product.service.MerchantGoodsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 启动时把某个区划前缀下、地图来源的聚落批量开城，**并重建商品池**（冷启动一次性动作）。
 *
 * <p>⚠️ <b>与 {@link EstateImportRunner} 上那句「启动时不许开城」是冲突的，
 * 这里说清为什么还是做了。</b> 那句话的理由是「出错时没有任何人拦得住」——
 * 反对的是把它做成**常驻默认**。这个 Bean 与导入那个一样：
 * 不配 {@code shop.community.open-map-region} 就<b>连注册都不注册</b>，
 * 配一次、跑一次、跑完把配置摘掉。它是一次<b>有人看着</b>的动作，
 * 不是一条每次启动都要想一下的分支。
 *
 * <p><b>开城之后必须紧接着重建商品池</b>，而且顺序不能反：
 * 池子是从 {@code reachableCommunities} 算的，而那个只看 <b>OPEN</b> 的聚落。
 * 先重建再开城的话，新开的那批一条池行都没有 —— 而症状与「开城没生效」
 * 一模一样：买家匹配到自家小区，然后看到一屏空货架。
 *
 * <p>排在导入之后（{@code @Order}）：同一次启动里既导入又开城时，
 * 要先有那些聚落才谈得上开它们。
 */
@Component
@Order(100)
@ConditionalOnProperty(name = "shop.community.open-map-region")
public class MapCommunityOpenRunner implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(MapCommunityOpenRunner.class);

    private final CommunityAdminService admin;
    private final MerchantGoodsService goods;
    private final String regionPrefix;

    public MapCommunityOpenRunner(CommunityAdminService admin, MerchantGoodsService goods,
                                  @Value("${shop.community.open-map-region}") String regionPrefix) {
        this.admin = admin;
        this.goods = goods;
        this.regionPrefix = regionPrefix;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            int opened = admin.openMapCommunities(regionPrefix, "SYSTEM");
            /*
             * **哪怕一个都没开也要重建池。** 上一次跑到一半崩掉的话，
             * 聚落已经是 OPEN 而池子还没建 —— 那时 opened=0，
             * 按「没开就不重建」处理会把那个中间态永久留下来，
             * 而它长得和「这个区没铺货」一模一样。
             */
            int touched = goods.resyncAllCommunityPools();
            LOG.info("[open-map] 前缀 {}：开城 {} 个，随后重建商品池（{} 件商品）",
                    regionPrefix, opened, touched);
        } catch (Exception e) {
            LOG.error("[open-map] 失败（不影响启动）：{}", e.toString());
        }
    }
}
