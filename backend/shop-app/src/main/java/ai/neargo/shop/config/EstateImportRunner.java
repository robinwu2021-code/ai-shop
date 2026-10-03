package ai.neargo.shop.config;

import ai.neargo.shop.community.service.CommunityAdminService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 启动时把一份地图小区扫描结果导进来（冷启动一次性动作）。
 *
 * <p><b>为什么要有它，而不是只留那个 ops 端点</b>：端点的入参是几千条 POI，
 * 得有人拿着运营令牌去 POST。而这件事要重复九次（深圳九个区），
 * 每次都靠人粘一次令牌，既慢又让令牌在不该出现的地方出现。
 * 做成跟着部署跑的一次性动作，用的是发版本来就有的那份权限。
 *
 * <p><b>默认不存在</b>：不配 {@code shop.community.estate-import.file} 这个 Bean 就不注册。
 * 配了才跑，跑完把结果打进日志 —— 而不是让它成为一条每次启动都要想一下的分支。
 *
 * <p><b>永远建成 CLOSED</b>，不给开关。导入与放出来是两步（见
 * {@link CommunityAdminService#importEstates}）：聚落一 OPEN 就同时参与买家匹配
 * 与商品池投影，而后者取决于商家经营范围。这里是启动期，没人在看屏幕 ——
 * 一个能在启动时批量开城的开关，出错时没有任何人拦得住。
 *
 * <p><b>出错不拖垮启动</b>：导入失败就记一条 error 走人。
 * 这是个补数据的动作，不该让线上因为一份 JSON 坏了而起不来。
 */
@Component
@ConditionalOnProperty(name = "shop.community.estate-import.file")
public class EstateImportRunner implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(EstateImportRunner.class);

    private final CommunityAdminService admin;
    private final ObjectMapper json;
    private final String file;

    public EstateImportRunner(CommunityAdminService admin, ObjectMapper json,
                              @Value("${shop.community.estate-import.file}") String file) {
        this.admin = admin;
        this.json = json;
        this.file = file;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            JsonNode d = json.readTree(Files.readString(Path.of(file)));
            String adcode = d.path("adcode").asString();
            /*
             * **扫描器留下的 failed 不是零就拒绝入库。**
             * 被限流吞掉的格子在返回上与「这儿没有小区」一模一样（count=0 + info=OK），
             * 所以那片是**未知**不是空 —— 导进来会变成一份看起来很满、
             * 实际系统性缺失的小区表，而缺失的那些小区里的买家会落进别人家的围栏。
             */
            int failed = d.path("failed").size();
            if (failed > 0) {
                LOG.error("[estate-import] {} 里有 {} 个格子扫失败 —— 那片是未知不是空，不导。补扫之后再来",
                        file, failed);
                return;
            }
            if (d.path("capped").asInt() > 0) {
                LOG.warn("[estate-import] 有 {} 个格子顶到了翻页天花板，那几片可能还有没取到的",
                        d.path("capped").asInt());
            }
            List<CommunityAdminService.EstateIn> items = new ArrayList<>();
            for (JsonNode x : d.path("items")) {
                items.add(new CommunityAdminService.EstateIn(
                        x.path("poiId").asString(), x.path("name").asString(),
                        x.path("address").asString(),
                        x.path("latE6").asInt(), x.path("lngE6").asInt()));
            }
            if (adcode.isBlank() || items.isEmpty()) {
                LOG.error("[estate-import] {} 里没有 adcode 或没有条目，不导", file);
                return;
            }
            var r = admin.importEstates(adcode, "CLOSED", false, items, "SYSTEM");
            LOG.info("[estate-import] {} ← {}：收到 {} 新建 {} 更新 {} 跳过 {}（全部 CLOSED，"
                            + "放出来要另走 /ops/communities/open-map）",
                    adcode, file, r.received(), r.created(), r.updated(), r.skipped());
        } catch (Exception e) {
            // 补数据的动作不该让线上起不来
            LOG.error("[estate-import] 导入失败（不影响启动）：{}", e.toString());
        }
    }
}
