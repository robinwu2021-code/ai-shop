package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.merchant.service.MerchantStoreService;
import ai.neargo.shop.merchant.service.MerchantStoreService.AreaCommand;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 保存门店资料时，同一条服务范围要**沿用原来的 area_no**。
 *
 * <p>门店级「这一路只服务其中几块」（mch_channel_area）按 area_no 引用范围行。
 * 此前每次保存都全删重插、换一批新号 —— 店主改一句地址，App 把范围原样带回，
 * 子集引用全部落空，那家店与主体足迹取交后变成空集，从买家端整家消失，保存提示成功。
 * 2026-09-28 给虹选粮油配「深圳测试店只送深圳」时撞到（前一晚保存过一次，盐湖区那条已经换了号）。
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("服务范围：重复保存不换 area_no")
class ServiceAreaKeepNoTest {

    private static final String ENTITY = "M-KEEPNO";
    private static final String STORE = "ST-KEEPNO";

    @Autowired private MerchantStoreService storeService;
    @Autowired private JdbcTemplate jdbc;

    @AfterEach
    void drop() {
        jdbc.update("delete from mch_service_area where entity_no=?", ENTITY);
        jdbc.update("delete from mch_store where entity_no=?", ENTITY);
        jdbc.update("delete from mch_entity where entity_no=?", ENTITY);
    }

    private void save(List<AreaCommand> areas) {
        storeService.save(ENTITY, null, new MerchantStoreService.SaveCommand(
                null, null, "08:00-20:00", "测试地址", null,
                List.of(), null, null, null, "PICKUP", areas, null, null));
    }

    private String areaNoOf(String refCode) {
        return jdbc.queryForObject("select area_no from mch_service_area where entity_no=? and ref_code=?",
                String.class, ENTITY, refCode);
    }

    @Test
    @DisplayName("★★★ 原样再存一次：两条范围的 area_no 都不变；新加的一条拿新号")
    void resaveKeepsAreaNo() {
        jdbc.update("insert into mch_entity(entity_no, name, status, created_at, updated_at) values (?, '保号测试', 'ACTIVE', now(), now())", ENTITY);
        jdbc.update("insert into mch_store(entity_no, store_no, name, is_default, created_at, updated_at) values (?, ?, '保号测试店', 1, now(), now())", ENTITY, STORE);

        save(List.of(new AreaCommand("CITY", "4403", "INCLUDE"), new AreaCommand("DISTRICT", "140802", "INCLUDE")));
        String sz = areaNoOf("4403");
        String yc = areaNoOf("140802");

        save(List.of(new AreaCommand("CITY", "4403", "INCLUDE"), new AreaCommand("DISTRICT", "140802", "INCLUDE"),
                new AreaCommand("DISTRICT", "330106", "INCLUDE")));

        assertThat(areaNoOf("4403")).isEqualTo(sz);
        assertThat(areaNoOf("140802")).isEqualTo(yc);
        assertThat(areaNoOf("330106")).isNotIn(sz, yc).isNotBlank();
    }
}
