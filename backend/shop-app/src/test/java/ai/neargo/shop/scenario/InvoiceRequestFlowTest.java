package ai.neargo.shop.scenario;

import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.auth.Realm;
import ai.neargo.shop.auth.SubjectKind;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.trade.entity.OrdInvoiceRequest;
import ai.neargo.shop.trade.entity.OrdOrder;
import ai.neargo.shop.trade.mapper.TradeMappers.OrderMapper;
import ai.neargo.shop.trade.service.InvoiceRequestService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 开票申请：**平台开给消费者**（ADR-017 §3.4 条件 2）。
 *
 * <p><b>这条链路此前完全不存在。</b> C 端只有下单前一句
 * 「本商家无法开具发票」——连申请的地方都没有。
 * 而归集路径要成立，四个必要条件缺一不可，第二条就是「平台开票给消费者」：
 * <b>没有入口 = 没有履行途径</b>，那是实质性缺失，不是体验问题。
 *
 * <p>本版是<b>手工开票</b>：运营在票据系统里开完，回来回填票号。
 * 条件 2 要的是「平台承担开票义务并实际履行」，不要求自动化。
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("开票申请：平台开给消费者，一单一票")
class InvoiceRequestFlowTest {

    @Autowired
    private InvoiceRequestService service;

    @Autowired
    private OrderMapper orderMapper;

    @Test
    @DisplayName("★★★ 已成交的单可以申请，金额落快照")
    void applyOnPaidOrder() {
        String order = anOrder(OrdOrder.PAID, 12_800L);

        var vo = service.apply(cmd(order, "张先生", null, "PERSONAL"));

        assertThat(vo.status()).isEqualTo(OrdInvoiceRequest.REQUESTED);
        // 快照而不是实时读订单：后续退款会改订单金额，而已开的票不会跟着变
        assertThat(vo.amountMinor()).isEqualTo(12_800L);
    }

    @Test
    @DisplayName("★★★ 一张订单只能申请一次 —— 重复申请 = 一笔交易两张票")
    void oneInvoicePerOrder() {
        String order = anOrder(OrdOrder.PAID, 10_000L);
        service.apply(cmd(order, "张先生", null, "PERSONAL"));

        // 这是税务问题不是体验问题，所以拦在申请这一步，而不是等运营开票时发现
        assertThatThrownBy(() -> service.apply(cmd(order, "李先生", null, "PERSONAL")))
                .isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("★★★ 被驳回后可以改抬头重申请 —— 改同一条，不插新的")
    void rejectedCanReapplyInPlace() {
        String order = anOrder(OrdOrder.PAID, 10_000L);
        var first = service.apply(cmd(order, "抬头写错了", null, "PERSONAL"));
        service.reject(first.requestNo(), "抬头与账户不符", "OPS001");

        var again = service.apply(cmd(order, "张先生", null, "PERSONAL"));

        // 插新的话同一订单会有两条，运营看到两条时分不清该开哪一张
        assertThat(again.requestNo()).isEqualTo(first.requestNo());
        assertThat(again.status()).isEqualTo(OrdInvoiceRequest.REQUESTED);
        assertThat(again.title()).isEqualTo("张先生");
        // 驳回原因要清掉，否则页面上会同时显示「开票中」和上一次的驳回理由
        assertThat(again.rejectReason()).isNull();
    }

    @Test
    @DisplayName("★★ 未支付的单不能申请 —— 没成交就没有可开的票")
    void unpaidCannotApply() {
        String order = anOrder(OrdOrder.WAIT_PAY, 10_000L);

        assertThatThrownBy(() -> service.apply(cmd(order, "张先生", null, "PERSONAL")))
                .isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("★★ 单位抬头缺税号要拒 —— 票开出来对方入不了账，等于白开")
    void companyTitleNeedsTaxNo() {
        String order = anOrder(OrdOrder.PAID, 10_000L);

        assertThatThrownBy(() -> service.apply(cmd(order, "某某公司", null, "COMPANY")))
                .isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("★★ 没有票号的「已开具」要拒 —— 消费者拿不到凭证，事后也查不到")
    void issuedNeedsInvoiceNo() {
        String order = anOrder(OrdOrder.PAID, 10_000L);
        var r = service.apply(cmd(order, "张先生", null, "PERSONAL"));

        assertThatThrownBy(() -> service.markIssued(r.requestNo(), "  ", "OPS001"))
                .isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("★★ 开完票不能再驳回 —— 票已经在对方手里，改状态改不回那张票")
    void issuedCannotBeRejected() {
        String order = anOrder(OrdOrder.PAID, 10_000L);
        var r = service.apply(cmd(order, "张先生", null, "PERSONAL"));
        service.markIssued(r.requestNo(), "INV-2026-0001", "OPS001");

        assertThatThrownBy(() -> service.reject(r.requestNo(), "反悔了", "OPS001"))
                .isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("★ 驳回必须写原因 —— 不写等于让消费者再猜一遍抬头哪里错了")
    void rejectNeedsReason() {
        String order = anOrder(OrdOrder.PAID, 10_000L);
        var r = service.apply(cmd(order, "张先生", null, "PERSONAL"));

        assertThatThrownBy(() -> service.reject(r.requestNo(), "", "OPS001"))
                .isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("★ 别人的订单申请不了 —— 属主校验在查询条件里，不是查出来再判")
    void cannotApplyForSomeoneElsesOrder() {
        String order = anOrder(OrdOrder.PAID, 10_000L);
        asBuyer("U-OTHER-" + System.nanoTime() % 100_000L);

        assertThatThrownBy(() -> service.apply(cmd(order, "张先生", null, "PERSONAL")))
                .isInstanceOf(BizException.class);
    }

    @AfterEach
    void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    // ---------------------------------------------------------------- fixtures

    private InvoiceRequestService.ApplyCommand cmd(String orderNo, String title,
                                                   String taxNo, String titleType) {
        return new InvoiceRequestService.ApplyCommand(
                orderNo, titleType, title, taxNo, "buyer@example.com");
    }

    private void asBuyer(String userNo) {
        var u = new LoginUser(Realm.CONSUMER, SubjectKind.USR, userNo, "测试买家",
                List.of(), List.of(), null, null);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(u, null, List.of()));
    }

    private String anOrder(String status, long payAmount) {
        String no = "ORDINV" + System.nanoTime() % 100_000_000L;
        String buyer = "UINV" + System.nanoTime() % 100_000_000L;
        OrdOrder o = new OrdOrder();
        o.setOrderNo(no);
        o.setUserNo(buyer);
        o.setStatus(status);
        o.setPayAmount(payAmount);
        orderMapper.insert(o);
        asBuyer(buyer);
        return no;
    }
}
