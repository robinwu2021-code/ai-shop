package ai.neargo.shop.notify.port;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「语义键:格名」那套配置（TDD-微信订阅消息优先 §2.3）。
 *
 * <p><b>这个文件钉的是生产上真在跑的那几行 `WX_TPL_*_FIELDS`。</b>
 * 领域只给业务语义键（{@code orderNo} / {@code result} / …），格名由通道按配置映射 ——
 * 中间任何一步错了，微信只回一个 47003（参数不符合规则），**不说是哪一格**，
 * 而发送失败在上层被当成「站内信照发」吞掉：界面正常、闸门全绿、一条微信都发不出去。
 *
 * <p>所以这里不测「能不能解析」这种套话，测的是**那几行配置配上那几处发送值，
 * 拼出来的 data 是不是微信收得下的样子**。
 */
class WxFieldedTemplateTest {

    // ---- 2026-10-09 写进生产 shop-app.env 的六行，逐字抄过来 ----
    private static final String AFTER_SALE_RESULT =
            "orderNo:character_string1,result:thing4,reason:thing11,time:time3";
    private static final String RETURN_WAIT =
            "afterSaleNo:character_string1,status:phrase3,tip:thing4,time:time2";
    private static final String GROUP_RESULT =
            "groupNo:character_string1,goods:thing2,result:phrase3,remark:thing4";
    private static final String DELIVERY_START =
            "orderNo:character_string6,status:phrase8,tip:thing5";
    private static final String MCH_NEW_ORDER =
            "orderNo:character_string4,amount:amount5,time:time2,tip:thing3";
    private static final String MCH_AFTER_SALE =
            "orderNo:character_string1,status:phrase3,tip:thing4";

    @Test
    @DisplayName("★★★ 六行配置都解析得出来，且格名就是模板里那几个 —— 对不上微信回 47003，不说哪一格")
    void productionFieldSpecsParse() {
        assertThat(WxSubscribeGateway.parseFieldMap(AFTER_SALE_RESULT))
                .containsExactly(entry("orderNo", "character_string1"), entry("result", "thing4"),
                        entry("reason", "thing11"), entry("time", "time3"));
        assertThat(WxSubscribeGateway.parseFieldMap(RETURN_WAIT))
                .containsExactly(entry("afterSaleNo", "character_string1"), entry("status", "phrase3"),
                        entry("tip", "thing4"), entry("time", "time2"));
        assertThat(WxSubscribeGateway.parseFieldMap(GROUP_RESULT))
                .containsExactly(entry("groupNo", "character_string1"), entry("goods", "thing2"),
                        entry("result", "phrase3"), entry("remark", "thing4"));
        assertThat(WxSubscribeGateway.parseFieldMap(DELIVERY_START))
                .containsExactly(entry("orderNo", "character_string6"), entry("status", "phrase8"),
                        entry("tip", "thing5"));
        assertThat(WxSubscribeGateway.parseFieldMap(MCH_NEW_ORDER))
                .containsExactly(entry("orderNo", "character_string4"), entry("amount", "amount5"),
                        entry("time", "time2"), entry("tip", "thing3"));
        assertThat(WxSubscribeGateway.parseFieldMap(MCH_AFTER_SALE))
                .containsExactly(entry("orderNo", "character_string1"), entry("status", "phrase3"),
                        entry("tip", "thing4"));
    }

    @Test
    @DisplayName("★★★ 时间格：领域给的是毫秒数，必须换成微信认的写法 —— 纯数字整条被拒")
    void epochMillisBecomesWechatDate() {
        Map<String, String> data = WxSubscribeGateway.fieldedData(
                WxSubscribeGateway.parseFieldMap(AFTER_SALE_RESULT),
                Map.of("orderNo", "SUB-1", "result", "未通过", "reason", "已拆封",
                        "time", "1791532800190"));
        assertThat(data.get("time3")).as("毫秒数直接发过去就是 47003").doesNotMatch("\\d+");
        assertThat(data.get("time3")).matches("\\d{4}年\\d{1,2}月\\d{1,2}日 \\d{2}:\\d{2}");
    }

    @Test
    @DisplayName("★★★ phrase 最多 5 个字 —— 超了整条被拒；我们填的三个状态词都在界内")
    void phraseStaysWithinFive() {
        for (String status : new String[]{"配送中", "待寄回", "待处理", "成功", "失败"}) {
            Map<String, String> data = WxSubscribeGateway.fieldedData(
                    WxSubscribeGateway.parseFieldMap(RETURN_WAIT),
                    Map.of("afterSaleNo", "AS-1", "status", status, "tip", "请尽快寄回"));
            assertThat(data.get("phrase3")).as(status).isEqualTo(status).hasSizeLessThanOrEqualTo(5);
        }
        // 真超长时要截断而不是原样发出去
        Map<String, String> over = WxSubscribeGateway.fieldedData(
                WxSubscribeGateway.parseFieldMap(RETURN_WAIT),
                Map.of("afterSaleNo", "AS-1", "status", "这是一个超过五个字的状态", "tip", "x"));
        assertThat(over.get("phrase3")).hasSizeLessThanOrEqualTo(5);
    }

    @Test
    @DisplayName("★★★ thing 最多 20 字：商家写的驳回理由可以任意长，截断而不是让整条发不出去")
    void longMerchantReasonIsClamped() {
        Map<String, String> data = WxSubscribeGateway.fieldedData(
                WxSubscribeGateway.parseFieldMap(AFTER_SALE_RESULT),
                Map.of("orderNo", "SUB-1", "result", "未通过", "time", "1791532800190",
                        "reason", "商品已经拆封并且影响二次销售，按平台规则本次申请不予通过，详情请看售后页"));
        assertThat(data.get("thing11")).hasSizeLessThanOrEqualTo(20);
    }

    @Test
    @DisplayName("★★ 模板选了的格一个都不能空 —— 空着整条被拒，填短横只是少一格")
    void missingValueBecomesDash() {
        // 商家没写驳回理由（remark 为空）是常态
        Map<String, String> data = WxSubscribeGateway.fieldedData(
                WxSubscribeGateway.parseFieldMap(AFTER_SALE_RESULT),
                Map.of("orderNo", "SUB-1", "result", "未通过", "time", "1791532800190"));
        assertThat(data).containsKeys("character_string1", "thing4", "thing11", "time3");
        assertThat(data.get("thing11")).isEqualTo("-");
        assertThat(data.values()).as("空值会让微信拒掉整条").noneMatch(String::isBlank);
    }

    @Test
    @DisplayName("★★ 金额按 amount 格原样过 —— 「12.80元」是微信的例子里那种写法")
    void amountPassesThrough() {
        Map<String, String> data = WxSubscribeGateway.fieldedData(
                WxSubscribeGateway.parseFieldMap(MCH_NEW_ORDER),
                Map.of("orderNo", "SUB-1", "amount", "12.80元", "time", "1791532800190", "tip", "请及时备货"));
        assertThat(data.get("amount5")).isEqualTo("12.80元");
    }

    @Test
    @DisplayName("★★ 配置写坏的那几种形状：少冒号 / 空段 / 尾随逗号 —— 跳过那一段，别让进程起不来")
    void brokenSpecDoesNotThrow() {
        assertThat(WxSubscribeGateway.parseFieldMap("orderNo:character_string1,坏的一段,result:thing4,"))
                .containsExactly(entry("orderNo", "character_string1"), entry("result", "thing4"));
        assertThat(WxSubscribeGateway.parseFieldMap("")).isEmpty();
        assertThat(WxSubscribeGateway.parseFieldMap(null)).isEmpty();
    }

    private static Map.Entry<String, String> entry(String k, String v) {
        Map<String, String> one = new LinkedHashMap<>();
        one.put(k, v);
        return one.entrySet().iterator().next();
    }
}
