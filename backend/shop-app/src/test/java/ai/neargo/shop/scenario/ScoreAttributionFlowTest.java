package ai.neargo.shop.scenario;

import ai.neargo.shop.merchant.dto.MerchantScoreVO;
import ai.neargo.shop.merchant.entity.MchEntity;
import ai.neargo.shop.merchant.mapper.MerchantMappers.MchEntityMapper;
import ai.neargo.shop.merchant.service.MerchantService;
import ai.neargo.shop.spi.user.MerchantQueryPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 评分的<b>责任归属</b>：标注，不过滤。
 *
 * <p>归集路径下平台是销售主体 —— 客服、配送、售后都是平台在做，
 * 而消费者打的「服务」与「时效」分同样落在这家店头上。
 * 拿它去考核供应商是<b>拿他控制不了的事罚他</b>。
 *
 * <p><b>但分照常展示。</b> 消费者打的是这次购物的真实体验，那个信息是真的；
 * 藏起来的直接后果是<b>没人为它负责</b> —— 供应商不背，平台也看不见。
 * 所以这里做的是「标出归属」，而不是「把分抹掉」。
 *
 * <p><b>商品分不在此列</b>：货是供应商的，品质问题该记在他头上 ——
 * 那正是考核要留下的那部分。三个维度一起标掉的话，考核就什么都不剩了。
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("评分责任归属：标注归属，不藏分")
class ScoreAttributionFlowTest {

    @Autowired
    private MerchantService merchantService;

    @Autowired
    private MchEntityMapper entityMapper;

    @Test
    @DisplayName("★★★ 归集：服务与时效标为平台承担，商品不标")
    void aggregatedMarksServiceAndSpeed() {
        String m = anEntity(MerchantQueryPort.FUNDS_AGGREGATED);

        MerchantScoreVO vo = merchantService.score(m);

        assertThat(vo.platformBorne()).containsExactlyInAnyOrder("service", "speed");
        // 货是供应商的 —— 商品分标掉的话考核就什么都不剩了
        assertThat(vo.platformBorne()).doesNotContain("goods");
    }

    @Test
    @DisplayName("★★★ 分照常给出，没有被抹掉 —— 藏起来等于没人为它负责")
    void scoresAreStillReported() {
        String m = anEntity(MerchantQueryPort.FUNDS_AGGREGATED);

        MerchantScoreVO vo = merchantService.score(m);

        assertThat(vo.scores().service()).isEqualTo(4.5d);
        assertThat(vo.scores().speed()).isEqualTo(4.0d);
        assertThat(vo.rating()).isEqualTo(4.6d);
    }

    @Test
    @DisplayName("★★ 直连：他自己就是销售主体，全部维度都算他的")
    void directBearsEverything() {
        String m = anEntity(MerchantQueryPort.FUNDS_DIRECT);

        assertThat(merchantService.score(m).platformBorne()).isEmpty();
    }

    // ---------------------------------------------------------------- fixtures

    private String anEntity(String fundsMode) {
        String no = "SA" + System.nanoTime() % 100_000_000L;
        MchEntity m = new MchEntity();
        m.setEntityNo(no);
        m.setName("评分归属测试主体");
        m.setLegalForm("ENTERPRISE");
        m.setStatus("ACTIVE");
        m.setFundsMode(fundsMode);
        m.setRating(46);
        m.setRatingCount(12);
        m.setScoreGoods(48);
        m.setScoreService(45);
        m.setScoreSpeed(40);
        entityMapper.insert(m);
        return no;
    }
}
