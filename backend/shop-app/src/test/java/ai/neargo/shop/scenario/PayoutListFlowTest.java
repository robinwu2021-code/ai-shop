package ai.neargo.shop.scenario;

import ai.neargo.shop.merchant.entity.MchPayoutAccount;
import ai.neargo.shop.merchant.mapper.MerchantMappers.PayoutAccountMapper;
import ai.neargo.shop.merchant.service.PayoutAccountCipher;
import ai.neargo.shop.pay.entity.StlBill;
import ai.neargo.shop.pay.mapper.SettleMappers.BillMapper;
import ai.neargo.shop.payclient.OpsPayoutListAppService;
import ai.neargo.shop.payclient.OpsPayoutListAppService.PayoutListVO;
import ai.neargo.shop.spi.user.MerchantQueryPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 付款清单导出（ADR-011 · TDD §3.4 · P2）。
 *
 * <p><b>这条链上钱真的会离开平台</b>，所以每条断言都落在
 * 「哪几家进了清单、哪几家没进、为什么」，而不是「接口返回 200」。
 *
 * <p>三道闸各测一条，另加一条「进了清单的账号必须是明文且可用」——
 * 后者是这个接口存在的全部理由：财务拿不到能转账的账号，前面三道闸都白设。
 */
@SpringBootTest
/*
 * **只要 test，不要 ops。**
 *
 * 这个类用的全是没有 @Profile 限制的 bean（service / mapper / cipher），
 * 不碰任何 ops controller —— 加上 ops 只会多一个 context key，
 * 而多一个 context 在这套测试里是有代价的：H2 是
 * `jdbc:h2:mem:shop;DB_CLOSE_DELAY=-1`，库在 context 关掉之后还活着，
 * 于是第二个 context 起来时 sql-init 会把 schema-test.sql 的种子再插一遍，
 * 撞 sys_industry 的主键 —— 症状是「Failed to load ApplicationContext」，
 * 与这个类本身毫无关系，而且**单独跑永远复现不了**。
 */
@ActiveProfiles("test")
class PayoutListFlowTest {

    private static final String E_OK = "E-PL-OK";
    private static final String E_NO_ACC = "E-PL-NOACC";
    private static final String E_NO_INV = "E-PL-NOINV";
    private static final String E_UNCONF = "E-PL-UNCONF";
    private static final String CARD = "6222020000111122223";

    @Autowired
    private OpsPayoutListAppService app;
    @Autowired
    private BillMapper bills;
    @Autowired
    private PayoutAccountMapper accounts;
    @Autowired
    private PayoutAccountCipher cipher;

    private String run;

    @BeforeEach
    void seed() {
        run = Long.toString(System.nanoTime());
        // ① 三条闸全过：已对账 + 票已核验 + 有生效账户
        bill(E_OK, StlBill.CONFIRMED, StlBill.INV_VERIFIED, 10_000);
        bill(E_OK, StlBill.CONFIRMED, StlBill.INV_NONE, 5_000);   // 无票供应商也算了结
        activeAccount(E_OK);
        // ② 有票有对账，但**没有收款账户**
        bill(E_NO_ACC, StlBill.CONFIRMED, StlBill.INV_VERIFIED, 20_000);
        // ③ 有账户、已对账，但**票没了结**
        bill(E_NO_INV, StlBill.CONFIRMED, StlBill.INV_PENDING, 30_000);
        activeAccount(E_NO_INV);
        // ④ 有账户有票，但**还没对账**
        bill(E_UNCONF, StlBill.PENDING_RECON, StlBill.INV_VERIFIED, 40_000);
        activeAccount(E_UNCONF);
    }

    @AfterEach
    void cleanup() {
        bills.delete(Wrappers.<StlBill>lambdaQuery()
                .in(StlBill::getEntityNo, E_OK, E_NO_ACC, E_NO_INV, E_UNCONF));
        accounts.delete(Wrappers.<MchPayoutAccount>lambdaQuery()
                .in(MchPayoutAccount::getEntityNo, E_OK, E_NO_ACC, E_NO_INV, E_UNCONF));
    }

    @Test
    @DisplayName("三条闸全过的才进清单，且账号是可用的明文")
    void onlyFullyEligibleRowsCarryPlaintextAccount() {
        PayoutListVO vo = app.list(null);

        var ok = vo.rows().stream().filter(r -> E_OK.equals(r.entityNo())).findFirst();
        assertThat(ok).isPresent();
        // 两张单合并，金额相加
        assertThat(ok.get().amountMinor()).isEqualTo(15_000);
        assertThat(ok.get().billCount()).isEqualTo(2);
        // **账号是明文且正是那一张卡** —— 掩码或密文都付不出去
        assertThat(ok.get().accountNumber()).isEqualTo(CARD);
        // 银行附言要能回勾，且给的是主体号不是商家名
        assertThat(ok.get().remark()).contains(E_OK);
        // 回填凭证号时按它逐张登记
        assertThat(ok.get().settleNos()).hasSize(2);
    }

    @Test
    @DisplayName("★ 没有生效收款账户的不进清单，且说得出原因")
    void missingActiveAccountIsBlockedWithReason() {
        PayoutListVO vo = app.list(null);

        assertThat(vo.rows()).noneMatch(r -> E_NO_ACC.equals(r.entityNo()));
        var b = vo.blocked().stream().filter(x -> E_NO_ACC.equals(x.entityNo())).findFirst();
        assertThat(b).isPresent();
        assertThat(b.get().reason()).contains("收款账户");
        // 金额要带上 —— 财务要知道「这一期少付了多少」
        assertThat(b.get().amountMinor()).isEqualTo(20_000);
    }

    @Test
    @DisplayName("票没了结、没对账的各自被挡，原因不同")
    void invoiceAndReconGatesGiveDistinctReasons() {
        PayoutListVO vo = app.list(null);

        assertThat(vo.rows()).noneMatch(r -> E_NO_INV.equals(r.entityNo()) || E_UNCONF.equals(r.entityNo()));
        String invReason = vo.blocked().stream()
                .filter(x -> E_NO_INV.equals(x.entityNo())).findFirst().orElseThrow().reason();
        String confReason = vo.blocked().stream()
                .filter(x -> E_UNCONF.equals(x.entityNo())).findFirst().orElseThrow().reason();

        assertThat(invReason).contains("进项票");
        assertThat(confReason).contains("对账");
        // **两条原因必须不同** —— 合成一句「条件不足」，财务就不知道该去催票还是去确认
        assertThat(invReason).isNotEqualTo(confReason);
    }

    @Test
    @DisplayName("合计只算进了清单的，不含被挡下的")
    void totalExcludesBlocked() {
        PayoutListVO vo = app.list(null);

        long rowsSum = vo.rows().stream().mapToLong(r -> r.amountMinor()).sum();
        assertThat(vo.totalMinor()).isEqualTo(rowsSum);
        // 被挡的 20000+30000+40000 一分都不能混进来，否则财务按一个错的数去备款
        assertThat(vo.totalMinor()).isLessThan(rowsSum + 90_000);
        assertThat(vo.blocked()).isNotEmpty();
    }

    private void activeAccount(String entityNo) {
        MchPayoutAccount a = new MchPayoutAccount();
        a.setAccountNo("PAC-" + run + "-" + entityNo);
        a.setEntityNo(entityNo);
        a.setAccountType(MchPayoutAccount.CORPORATE);
        a.setAccountName("测试主体");
        a.setAccountNumberEnc(cipher.encrypt(CARD));
        a.setAccountMasked("****2223");
        a.setBankName("浦发银行");
        a.setStatus(MchPayoutAccount.ACTIVE);
        a.setTenantNo("MAIN");
        a.setCreatedAt(LocalDateTime.now());
        a.setUpdatedAt(LocalDateTime.now());
        accounts.insert(a);
    }

    private void bill(String entityNo, String status, String invoiceStatus, long net) {
        StlBill b = new StlBill();
        String no = "SB-PL-" + run + "-" + entityNo + "-" + net;
        b.setSettleNo(no);
        b.setSubOrderNo("SUB-" + no);
        b.setOrderNo("SO-" + no);
        b.setEntityNo(entityNo);
        b.setGrossMinor(net);
        b.setCommissionMinor(0L);
        b.setServiceFeeMinor(0L);
        b.setNetMinor(net);
        b.setAccruedAt(System.currentTimeMillis());
        b.setStatus(status);
        b.setInvoiceStatus(invoiceStatus);
        b.setBusinessMode(MerchantQueryPort.MODE_SELF_OPERATED);
        b.setTenantNo("MAIN");
        b.setCreatedAt(LocalDateTime.now());
        b.setUpdatedAt(LocalDateTime.now());
        bills.insert(b);
    }
}
