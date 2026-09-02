package ai.neargo.shop.scenario;

import ai.neargo.shop.merchant.service.MerchantPaymentService;
import ai.neargo.shop.spi.user.MerchantQueryPort;
import ai.neargo.shop.spi.pay.PayChannelMasterPort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 支付方式这条链两头都断着（S13）。
 *
 * <h2>量到的实况</h2>
 * <ul>
 *   <li>{@code sys_pay_channel.pay_methods} <b>有值，但没有任何决策读它</b> ——
 *       只映射进一个前端不读的 VO；</li>
 *   <li>{@code mch_payment_merchant.pay_methods} <b>有读取方，却从没被写过</b> ——
 *       全项目 {@code setPayMethods} 只出现在三个测试文件里；</li>
 *   <li>而中间那段逻辑写得很仔细：结算页与收银台都按它求交集，
 *       空集当「未配置」跳过、未配置返回 null 而不是空数组。</li>
 * </ul>
 *
 * <p><b>于是那条判据从来没有真正生效过</b>，而它不生效的表现是「一切正常」：
 * 交集永远不收窄，所有方式都可用。这是「只写不读」的镜像 ——
 * <b>只读不写</b>，同样零报错。
 *
 * <p>本类钉的是接上之后那条链真的通了，且<b>不是靠替身通的</b>。
 */
@SpringBootTest
@ActiveProfiles("test")
class PayMethodsWiredThroughTest {

    private static final String ENTITY = "M-PM-WIRE";
    private static final String STORE = "S-PM-WIRE";

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PayChannelMasterPort channelPort;
    @Autowired
    private MerchantPaymentService paymentService;
    @Autowired
    private MerchantQueryPort merchantPort;

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM mch_payment_merchant WHERE entity_no = ?", ENTITY);
        jdbc.update("DELETE FROM mch_store WHERE entity_no = ?", ENTITY);
        jdbc.update("DELETE FROM mch_entity WHERE entity_no = ?", ENTITY);
    }

    private void fixture() {
        jdbc.update("INSERT INTO mch_entity (entity_no, name, created_at, updated_at)"
                + " VALUES (?,?,NOW(),NOW())", ENTITY, "支付方式接线测试主体");
        jdbc.update("INSERT INTO mch_store (entity_no, store_no, created_at, updated_at)"
                + " VALUES (?,?,NOW(),NOW())", ENTITY, STORE);
    }

    /** 主体激活时那条占位记录：进件从它开始推进 */
    private void placeholder(String channel, String payMethods) {
        jdbc.update("INSERT INTO mch_payment_merchant (entity_no, store_no, pay_channel,"
                        + " legal_form, apply_status, pay_methods, created_at, updated_at)"
                        + " VALUES (?,?,?,?,?,?,NOW(),NOW())",
                ENTITY, "", channel, "MICRO", "NONE", payMethods);
    }

    private MerchantPaymentService.SubmitCommand submitCmd(String channel) {
        return new MerchantPaymentService.SubmitCommand(channel, "PERSONAL",
                "6222020000999912345", java.util.List.of("ID_CARD_FRONT", "ID_CARD_BACK"),
                "测试联系人", "13900000000", "");
    }

    private String storedMethods() {
        var rows = jdbc.queryForList(
                "SELECT pay_methods FROM mch_payment_merchant WHERE entity_no = ?",
                String.class, ENTITY);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    @Test
    @DisplayName("★★★ 通道那份清单读得出来，且不带反斜杠 —— 用 Jackson 解会在 H2 里静默变空集")
    void channelMethodsParseAcrossDialects() {
        var ms = channelPort.payMethodsOf("WECHAT");

        assertThat(ms)
                .as("通道支持的方式读成了空 —— 下游那份商家清单就永远写不进去，"
                        + "而空集在交集逻辑里正好当「未配置」跳过，一路都不报错")
                .isNotEmpty();
        assertThat(ms).contains("JSAPI");
        assertThat(ms)
                .as("带着反斜杠说明按 JSON 解了：种子写的是 '[\\\"JSAPI\\\"]'，"
                        + "MariaDB 解成 [\"JSAPI\"]，H2 原样存下带反斜杠的那份")
                .allSatisfy(m -> assertThat(m).doesNotContain("\\"));
    }

    @Test
    @DisplayName("★★ 查不到的通道给空列表，不兜一份默认 —— 兜了就是替一个不存在的通道承诺能力")
    void unknownChannelGivesEmpty() {
        assertThat(channelPort.payMethodsOf("NO_SUCH_CHANNEL")).isEmpty();
        assertThat(channelPort.payMethodsOf(null)).isEmpty();
    }

    @Test
    @DisplayName("★★★ 进件通过之后那一列终于有内容 —— 此前生产代码从没写过它")
    void activationWritesMethods() {
        fixture();
        placeholder("WECHAT", null);
        assertThat(storedMethods()).as("前置：这一列本来是空的").isNull();

        /*
         * 走 submit 而不是手插一个申请单号再 refresh。
         *
         * 替身只对**它自己发过的**申请单返回 ACTIVE，手插的号它如实答「审核中」
         * （那是对的：查一个不存在的单不该编一个结果）。
         * 于是手插版本永远走不到开户成功那条分支 —— 用例会以「这一列还是空的」
         * 报红，而红的原因与被测的那行代码毫无关系。
         */
        paymentService.submit(ENTITY, submitCmd("WECHAT"));

        assertThat(storedMethods())
                .as("开户成功后仍是空 —— 那么结算页与收银台的交集判据输入永远为空，"
                        + "它写得再仔细也不会生效")
                .isNotNull().contains("JSAPI");

        // 判据的下游：能力查询真的拿得到
        var cap = merchantPort.payCapabilityOf(ENTITY, STORE);
        assertThat(cap.payMethods())
                .as("落库了但读不出来 —— 那是 readList 用 Jackson 解到了带反斜杠的那份")
                .contains("JSAPI");
    }

    @Test
    @DisplayName("★★★ 重推回执不覆盖已收窄的范围 —— 覆盖会把运营收回去的方式又放回来")
    void repeatedCallbackDoesNotOverwrite() {
        fixture();
        placeholder("WECHAT", "[\"JSAPI\"]");

        paymentService.submit(ENTITY, submitCmd("WECHAT"));
        // 再回查一次：通道重推是常态
        paymentService.refresh(ENTITY, "WECHAT", "");

        assertThat(merchantPort.payCapabilityOf(ENTITY, STORE).payMethods())
                .as("通道重推回执是常态。每次覆盖的话，收窄过的范围会被悄悄放回去 ——"
                        + "与 payMerchantNo「只生成一次」同一条理由")
                .containsExactly("JSAPI");
    }
}
