package ai.neargo.shop.config;

import ai.neargo.shop.community.service.CommunityAdminService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 启动时把某个区划前缀下、地图来源的聚落批量开城（冷启动一次性动作）。
 *
 * <p>⚠️ <b>与 {@link EstateImportRunner} 上那句「启动时不许开城」是冲突的，
 * 这里说清为什么还是做了。</b> 那句话的理由是「出错时没有任何人拦得住」——
 * 反对的是把它做成**常驻默认**。这个 Bean 与导入那个一样：
 * 不配 {@code shop.community.open-map-region} 就<b>连注册都不注册</b>，
 * 配一次、跑一次、跑完把配置摘掉。它是一次<b>有人看着</b>的动作，
 * 不是一条每次启动都要想一下的分支。
 *
 * <p>开城之后不用再做任何事：买家能看到什么在查询时按「小区是否开放」现算
 * （方案-商品可见性改查询时关联）。此前这里紧接着要全量重建商品池，顺序反了就是一屏空货架。
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
    private final String regionPrefix;

    public MapCommunityOpenRunner(CommunityAdminService admin,
                                  @Value("${shop.community.open-map-region}") String regionPrefix) {
        this.admin = admin;
        this.regionPrefix = regionPrefix;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            int opened = admin.openMapCommunities(regionPrefix, "SYSTEM");
            LOG.info("[open-map] 前缀 {}：开城 {} 个", regionPrefix, opened);
        } catch (Exception e) {
            LOG.error("[open-map] 失败（不影响启动）：{}", e.toString());
        }
    }
}
