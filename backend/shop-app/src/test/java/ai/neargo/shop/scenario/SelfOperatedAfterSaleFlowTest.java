package ai.neargo.shop.scenario;

import ai.neargo.shop.merchant.entity.MchEntity;
import ai.neargo.shop.merchant.mapper.MerchantMappers.MchEntityMapper;
import ai.neargo.shop.spi.user.MerchantQueryPort;
import ai.neargo.shop.trade.entity.OrdAfterSale;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.mapper.TradeMappers.AfterSaleMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.SubOrderMapper;
import ai.neargo.shop.trade.service.AfterSaleService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import org.junit.jupiter.api.AfterEach;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 自营单的售后**直接进平台仲裁**，不派给商家。
 *
 * <p><b>这不是产品优化，是 ADR-017 §3.4 条件 3 的落地</b>：
 * 归集路径下平台是法律上的销售主体 —— 合同相对方是平台、票是平台开的、
 * 钱在平台账户。那么<b>平台对消费者承担商品与售后责任，再向商家追偿</b>。
 * 做不到这一条，「自营」就不成立，整条资金链退回第三方模式。
 *
 * <p>此前自营单同样派给「商家」—— 而那个商家就是平台自己：
 * 消费者申请退款 → 等平台自己审 → 驳回后再升级给平台仲裁。
 * <b>一条本该一步的路走了两段，中间那段还是平台审自己。</b>
 *
 * <p>判据用 {@code funds_mode} 而不是门店的 {@code business_mode}：
 * <b>责任跟着钱走</b> —— 钱在谁账户，谁就是那个要先赔的人。
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("自营售后：平台先扛，不派给商家")
class SelfOperatedAfterSaleFlowTest {

    @Autowired
    private AfterSaleService afterSaleService;

    @Autowired
    private MchEntityMapper entityMapper;

    @Autowired
    private SubOrderMapper subOrderMapper;

    @Autowired
    private AfterSaleMapper afterSaleMapper;

    @Test
    @DisplayName("★★ 归集（平台是销售主体）→ 直接 ARBITRATING")
    void aggregatedGoesStraightToArbitration() {
        String sub = aPaidSubOrder(MerchantQueryPort.FUNDS_AGGREGATED);

        afterSaleService.apply(sub, cmd());

        assertThat(latest(sub).getStatus()).isEqualTo(OrdAfterSale.ARBITRATING);
    }

    @Test
    @DisplayName("★★ 直连（商家是销售主体）→ 仍走 APPLIED，由商家先审")
    void directStillGoesToMerchant() {
        String sub = aPaidSubOrder(MerchantQueryPort.FUNDS_DIRECT);

        afterSaleService.apply(sub, cmd());

        // 钱在商家账户、票是商家开的，那么先由他处理是对的 ——
        // 这条不能一起改掉，否则平台在替一个不该他负责的关系担责
        assertThat(latest(sub).getStatus()).isEqualTo(OrdAfterSale.APPLIED);
    }

    @Test
    @DisplayName("★ 自营单不出现在商家的售后待办里 —— 那条队列是给第三方商家的")
    void selfOperatedNotInMerchantQueue() {
        String entityNo = anEntity(MerchantQueryPort.FUNDS_AGGREGATED);
        String sub = aPaidSubOrder(entityNo);

        afterSaleService.apply(sub, cmd());

        var queue = afterSaleService.merchantList(entityNo, OrdAfterSale.APPLIED);
        assertThat(queue).noneSatisfy(v -> assertThat(v.subOrderNo()).isEqualTo(sub));
    }

    @AfterEach
    void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    // ---------------------------------------------------------------- fixtures

    /**
     * 售后申请要消费者登录态（{@code ownSubOrder} 校验「这单是不是你的」）——
     * 那条校验不能为测试去掉：不校验的话任何人都能给别人的订单申请退款。
     */
    private void asBuyer(String userNo) {
        var u = new ai.neargo.shop.auth.LoginUser(
                ai.neargo.shop.auth.Realm.CONSUMER, ai.neargo.auth.store.SubjectKind.USR, userNo, "测试买家",
                List.of(), List.of(), null, null);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(u, null, List.of()));
    }

    private AfterSaleService.ApplyCommand cmd() {
        return new AfterSaleService.ApplyCommand(
                OrdAfterSale.REFUND_ONLY, "不想要了", List.of(), 50_000L);
    }

    private OrdAfterSale latest(String subOrderNo) {
        return ai.neargo.common.data.scope.DataScopeContext.executeWithoutScope(() ->
                afterSaleMapper.selectOne(Wrappers.<OrdAfterSale>lambdaQuery()
                        .eq(OrdAfterSale::getSubOrderNo, subOrderNo)
                        .orderByDesc(OrdAfterSale::getId).last("LIMIT 1")));
    }

    private String anEntity(String fundsMode) {
        String no = "AS" + System.nanoTime() % 100_000_000L;
        MchEntity m = new MchEntity();
        m.setEntityNo(no);
        m.setName("售后分流测试主体");
        m.setLegalForm("ENTERPRISE");
        m.setStatus("ACTIVE");
        m.setFundsMode(fundsMode);
        entityMapper.insert(m);
        return no;
    }

    private String aPaidSubOrder(String fundsModeOrEntityNo) {
        String entityNo = fundsModeOrEntityNo.startsWith("AS")
                ? fundsModeOrEntityNo : anEntity(fundsModeOrEntityNo);
        String no = "SUBAS" + System.nanoTime() % 100_000_000L;
        OrdSubOrder s = new OrdSubOrder();
        s.setSubOrderNo(no);
        s.setOrderNo("ORDAS" + System.nanoTime() % 100_000_000L);
        String buyer = "UAS" + System.nanoTime() % 100_000_000L;
        s.setUserNo(buyer);
        asBuyer(buyer);
        s.setEntityNo(entityNo);
        s.setStatus("COMPLETED");
        // **金额要高于极速退阈值**（默认 100 元）。低于阈值的仅退款会自动通过 →
        // 直接 REFUNDED，根本走不到「派给谁」这一步。
        // 那条捷径本来就该优先，所以测分流必须避开它，而不是把它关掉
        s.setPayAmount(50_000L);
        subOrderMapper.insert(s);
        return no;
    }
}
