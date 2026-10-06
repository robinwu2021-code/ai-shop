package ai.neargo.shop.arch;

import ai.neargo.shop.product.service.MerchantGoodsService.SaveCommand;
import ai.neargo.shop.product.service.MerchantGoodsService.Sku;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 在售商品改价/限购「发布后不生效」排查的回归守卫（设计-发布与销售地区优化 #1）。
 *
 * <p>在售商品的保存只写草稿：{@code SaveCommand} 被序列化进 {@code prd_goods_draft.payload}，
 * 发布时再反序列化回来重放。此前**序列化用注入的 Jackson 3（配过的 json bean），
 * 反序列化却 new 了一只临时 Jackson 2** —— 这是整条链里唯一能让某些字段静默丢失的地方：
 * 若 json bean 配了非默认命名/包含策略，Jackson 2 读不回。
 *
 * <p>本测试用**容器里真正那只 json bean**（而非 default mapper）做 round-trip，
 * 锁住划线价（{@code skus[].originPrice}）与每人限购（{@code limitPerUser}）两字段
 * 必须原样回来。统一反序列化为同一只 bean 之后，这条恒绿；
 * 哪天有人把 json bean 配出不对称、或把反序列化换回别的 mapper，这条会红。
 */
@SpringBootTest
@ActiveProfiles("test")
class DraftPayloadRoundTripTest {

    @Autowired
    private ObjectMapper json;   // 容器里配过的 Jackson 3，与写 payload 的是同一只

    @Test
    @DisplayName("★★ 草稿 payload round-trip：划线价与每人限购原样保留")
    void linePriceAndLimitSurviveRoundTrip() {
        Sku sku = new Sku("SKU_T", List.of("500g"), 1999L, null, 10,
                2999L,      // originPrice 划线价
                null, null, "BAR1", "MSKU1", "袋");
        SaveCommand cmd = new SaveCommand(
                "G_T", "测试品", null, null, null,
                "CAT120", "cover.jpg", List.of("a.jpg"),
                null, List.of(sku),
                List.of("EXPRESS"),
                5,          // limitPerUser 每人限购
                null, null, null, null, null, null, null, "NORMAL");

        String payload = json.writeValueAsString(cmd);
        SaveCommand back = json.readValue(payload, SaveCommand.class);

        assertThat(back.limitPerUser()).as("每人限购必须 round-trip 回来").isEqualTo(5);
        assertThat(back.skus()).hasSize(1);
        assertThat(back.skus().get(0).originPrice()).as("划线价必须 round-trip 回来").isEqualTo(2999L);
        assertThat(back.skus().get(0).price()).isEqualTo(1999L);
        assertThat(back.skus().get(0).stock()).isEqualTo(10);
    }
}
