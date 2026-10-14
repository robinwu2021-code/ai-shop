package ai.neargo.shop.scenario;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.pay.PointsService;
import ai.neargo.shop.trade.entity.OrdOrder;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.mapper.TradeMappers;
import ai.neargo.shop.spi.settle.PointsPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 积分规则的交接（M9 · 2026-09-01）：<b>规则由调用方传进来，支付域不回查 product</b>。
 *
 * <h2>这组守的是交接里唯一会静默出错的那一处</h2>
 * {@code EarnLine.rule} 有三种取值，而其中两种<b>长得很像、含义相反</b>：
 * <ul>
 *   <li>{@code null} —— 商品与类目两层都没配，支付域该用<b>平台兜底比例</b>；</li>
 *   <li>{@code EarnRule(FIXED, 0)} —— <b>明确配了 0 分</b>（储值卡就是这么配的），
 *       支付域该如实发 0。</li>
 * </ul>
 * 交接前这个区分由 {@code Optional.empty()} 承担，交接后由 {@code null} 承担。
 * 转换那一行写成 {@code orElse(new EarnRule(FIXED, 0))} 就会把两者合成一个 ——
 * <b>储值卡从「发 0 分」变成「按兜底比例发分」，而两边都不报错</b>。
 * 这是多层配置最常见的那个 bug，M9 给了它一次新的机会。
 *
 * <p>本类不测优先级（商品例外 → 类目），那是 {@code PointsRuleFlowTest} 的事。
 * 这里测的是<b>规则到了支付域之后被怎么用</b>。
 */
@SpringBootTest
@ActiveProfiles("test")
class PointsRuleHandoffTest {

    /** 种子里的商家。要用真实存在的那个 —— 发分要过商家的积分开关 */
    private static final String MERCHANT = "M0001";

    @Autowired
    private PointsService points;
    @Autowired
    private ai.neargo.shop.trade.mapper.TradeMappers.OrderMapper orderMapper;
    @Autowired
    private ai.neargo.shop.trade.mapper.TradeMappers.SubOrderMapper subOrderMapper;
    @Autowired
    private ai.neargo.shop.merchant.mapper.MerchantMappers.MchEntityMapper merchantMapper;
    @Autowired
    private ai.neargo.shop.community.mapper.CommunityMappers.CommunityMapper communityMapper;

    /** 本用例真的打开过的那些，@AfterEach 只关这些回去 */
    private final java.util.Set<String> merchantOpened = new java.util.HashSet<>();
    private final java.util.Set<String> communityOpened = new java.util.HashSet<>();

    /**
     * 积分开关默认是<b>关</b>的，而关着的时候发分恒返回 0 ——
     * 不开就跑的话，下面每一条断言都会「因为正确的理由之外的理由」变绿。
     * 第一版就是这么跑的，靠对照量露的馅。
     */
    @org.junit.jupiter.api.BeforeEach
    void openPointsSwitches() {
        ai.neargo.common.data.scope.DataScopeContext.executeWithoutScope(() -> {
            for (var m : merchantMapper.selectList(null)) {
                if (!Boolean.TRUE.equals(m.getPointsEnabled())) {
                    merchantOpened.add(m.getEntityNo());
                    m.setPointsEnabled(true);
                    merchantMapper.updateById(m);
                }
            }
            for (var c : communityMapper.selectList(null)) {
                if (!Boolean.TRUE.equals(c.getPointsEnabled())) {
                    communityOpened.add(c.getCommunityNo());
                    c.setPointsEnabled(true);
                    communityMapper.updateById(c);
                }
            }
            return null;
        });
    }

    /**
     * ⚠️ <b>只把本用例真的打开过的那些关回去。</b>
     *
     * 留着开的话，此后所有下单链路都会真的发分、真的建积分账户 ——
     * 而别的用例里「这个用户还没有积分账户」是个隐含前提。
     * 撞上就是 DuplicateKeyException，而报错里一个字都不会提到积分开关，
     * 表现是「单独跑绿、全量跑红」。
     */
    @org.junit.jupiter.api.AfterEach
    void restorePointsSwitches() {
        if (merchantOpened.isEmpty() && communityOpened.isEmpty()) {
            return;
        }
        ai.neargo.common.data.scope.DataScopeContext.executeWithoutScope(() -> {
            for (var m : merchantMapper.selectList(null)) {
                if (merchantOpened.contains(m.getEntityNo())) {
                    m.setPointsEnabled(false);
                    merchantMapper.updateById(m);
                }
            }
            for (var c : communityMapper.selectList(null)) {
                if (communityOpened.contains(c.getCommunityNo())) {
                    c.setPointsEnabled(false);
                    communityMapper.updateById(c);
                }
            }
            return null;
        });
        merchantOpened.clear();
        communityOpened.clear();
    }

    /**
     * 发一笔分，返回实发分数。基数固定 10000 分（100 元）。
     *
     * <p>要先落一笔真实的已支付子单：发分会核对这个人这一单能不能发。
     * 造一个不存在的单号的话<b>一律发 0</b>，而那会让下面每条断言都「绿得毫无意义」。
     * 这不是假设 —— 第一版就是那么写的，靠对照量当场露的馅。
     */
    private long grantWith(PointsPort.EarnRule rule) {
        String subOrderNo = paidSubOrder("U-PTS-HANDOFF");
        return points.grantOnPay("U-PTS-HANDOFF", MERCHANT,
                List.of(new PointsPort.EarnLine("G-HANDOFF", "C-HANDOFF", 10_000L, rule)),
                subOrderNo, "WECHAT", null).points();
    }

    private String paidSubOrder(String userNo) {
        String orderNo = "ORD-PTSH-" + System.nanoTime();
        String subOrderNo = "SUB-" + orderNo;
        ai.neargo.common.data.scope.DataScopeContext.executeWithoutScope(() -> {
            var o = new ai.neargo.shop.trade.entity.OrdOrder();
            o.setOrderNo(orderNo);
            o.setUserNo(userNo);
            o.setStatus(ai.neargo.shop.trade.entity.OrdOrder.PAID);
            o.setPayAmount(10_000L);
            o.setPayChannel("WECHAT");
            orderMapper.insert(o);

            var sub = new ai.neargo.shop.trade.entity.OrdSubOrder();
            sub.setSubOrderNo(subOrderNo);
            sub.setOrderNo(orderNo);
            sub.setUserNo(userNo);
            sub.setEntityNo(MERCHANT);
            sub.setStatus(ai.neargo.shop.trade.entity.OrdOrder.PAID);
            sub.setPayAmount(10_000L);
            subOrderMapper.insert(sub);
            return null;
        });
        return subOrderNo;
    }


    @Test
    @DisplayName("★★★ 明确配 0 分就发 0 —— 不能掉到平台兜底，储值卡靠的就是这一条")
    void explicitZeroIsNotFallback() {
        long withExplicitZero = grantWith(new PointsPort.EarnRule(PointsPort.FIXED, 0));
        long withNothingConfigured = grantWith(null);

        assertThat(withExplicitZero)
                .as("配了 FIXED 0 却发了分 —— 说明「配了 0」被当成了「没配」")
                .isZero();
        /*
         * **对照量**：没配那条必须真的发出分来，否则上面那条零值毫无意义 ——
         * 兜底比例被配成 0 的话，两条都是 0 而这个用例照样绿。
         */
        assertThat(withNothingConfigured)
                .as("没配那条也发了 0 —— 那上面那条断言什么都没证明，"
                        + "先去看平台兜底比例是不是被配成 0 了")
                .isPositive();
    }

    @Test
    @DisplayName("★★ 定额就是定额，与基数无关 —— 传错成比例的话金额一变就露馅")
    void fixedIsIndependentOfBase() {
        assertThat(grantWith(new PointsPort.EarnRule(PointsPort.FIXED, 33))).isEqualTo(33);
    }

    @Test
    @DisplayName("★★ 比例按万分比整数算 —— 用浮点的话对账时的分位差没人说得清")
    void ratioUsesBasisPoints() {
        // 100 元 = 10000 分，千分之一 = 万分比 10 → 10 分
        assertThat(grantWith(new PointsPort.EarnRule(PointsPort.RATIO, 10))).isEqualTo(10);
        // 比例配成 0 也是「明确不发」，同样不掉兜底
        assertThat(grantWith(new PointsPort.EarnRule(PointsPort.RATIO, 0))).isZero();
    }

    @Test
    @DisplayName("★★★ 支付域不认识 product 的类型 —— 编译期还连着的依赖不算断")
    void payDoesNotKnowProductTypes() throws Exception {
        Class<?> impl = Class.forName("ai.neargo.shop.pay.impl.PointsServiceImpl");

        List<String> productDeps = java.util.Arrays.stream(impl.getDeclaredFields())
                .map(f -> f.getType().getName())
                .filter(n -> n.startsWith("ai.neargo.shop.spi.product"))
                .toList();

        assertThat(productDeps)
                .as("支付域又拿回了 product 的 Port。M9 把规则改成传入，"
                        + "就是为了让这条依赖不存在 —— 加回来的话 pay-svc 独立部署时"
                        + "它会变成一条反向 HTTP 调用，而那正是要拆掉的东西")
                .isEmpty();
        // 对照量：这个类确实有字段，反射没有扫空
        assertThat(impl.getDeclaredFields()).isNotEmpty();
    }

    // ───────────────────────── 转换那一步本身

    @Test
    @DisplayName("★★★ 「没配」翻成 null，不能翻成 FIXED 0 —— 两者含义相反")
    void handoffKeepsNotConfiguredDistinct() {
        assertThat(ai.neargo.shop.trade.service.impl.PointsRuleHandoff
                .toPayRule(java.util.Optional.empty()))
                .as("「两层都没配」被翻成了一个规则 —— 支付域就再也落不到平台兜底了。"
                        + "写成 orElse(new EarnRule(FIXED, 0)) 看着更安全（少一个 null），"
                        + "实际是把两种相反的含义合成了一个")
                .isNull();
    }

    @Test
    @DisplayName("★★ 「明确配 0」原样翻过去 —— 上一条不能靠「什么都翻成 null」通过")
    void handoffKeepsExplicitZero() {
        var out = ai.neargo.shop.trade.service.impl.PointsRuleHandoff.toPayRule(
                java.util.Optional.of(new ai.neargo.shop.spi.product.PointsRulePort
                        .EarnRule(ai.neargo.shop.spi.product.PointsRulePort.FIXED, 0)));

        assertThat(out).isNotNull();
        assertThat(out.mode()).isEqualTo(PointsPort.FIXED);
        assertThat(out.value()).isZero();
    }

    @Test
    @DisplayName("★ 比例值原样带过去 —— 万分比在两边是同一个口径")
    void handoffKeepsRatioValue() {
        var out = ai.neargo.shop.trade.service.impl.PointsRuleHandoff.toPayRule(
                java.util.Optional.of(new ai.neargo.shop.spi.product.PointsRulePort
                        .EarnRule(ai.neargo.shop.spi.product.PointsRulePort.RATIO, 10)));

        assertThat(out.mode()).isEqualTo(PointsPort.RATIO);
        assertThat(out.value()).isEqualTo(10);
    }
}
