package ai.neargo.shop.logistics.channel.kuaidi100;

import ai.neargo.shop.common.ExpressCompanies;
import ai.neargo.shop.spi.logistics.TraceResult;
import ai.neargo.shop.spi.logistics.TraceStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 快递100 实时查询 provider（TDD-快递100轨迹查询）。不连快递100：测签名、解析、状态映射与降级。
 * 真实返回另用真单号实跑，记在 TDD §5。
 */
@DisplayName("快递100 轨迹查询：签名、解析、映射、降级")
class Kuaidi100TraceProviderTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    @DisplayName("★★★ 签名 = MD5(param + key + customer) 32 位大写 —— 拼接顺序错了快递100 回 503")
    void signIsUpperMd5OfParamKeyCustomer() {
        // MD5("abc") 的公认值：顺序 param=a、key=b、customer=c 拼成 abc
        assertThat(Kuaidi100TraceProvider.sign("a", "b", "c")).isEqualTo("900150983CD24FB0D6963F7D28E17F72");
    }

    @Test
    @DisplayName("★★★ 成功返回：节点按时间倒序、带地点，整单状态按 state 归一")
    void parsesSuccessBody() {
        String body = """
                {"message":"ok","nu":"SF123","ischeck":"0","com":"shunfeng","status":"200","state":"0","condition":"F00",
                 "data":[
                  {"time":"2026-10-07 09:00:00","ftime":"2026-10-07 09:00:00","context":"【深圳市】已揽收","areaName":"广东,深圳市","status":"揽收"},
                  {"time":"2026-10-08 10:30:00","ftime":"2026-10-08 10:30:00","context":"快件已到达【运城转运中心】","areaName":"山西,运城市","status":"在途"}
                 ]}""";
        var r = Kuaidi100TraceProvider.parse(json, "SF", "SF123", body);
        assertThat(r).isPresent();
        TraceResult t = r.get();
        assertThat(t.provider()).isEqualTo("kuaidi100");
        assertThat(t.status()).isEqualTo(TraceStatus.IN_TRANSIT);
        assertThat(t.nodes()).hasSize(2);
        assertThat(t.nodes().get(0).info()).as("最新在前").contains("运城转运中心");
        assertThat(t.nodes().get(0).location()).isEqualTo("山西,运城市");
        assertThat(t.nodes().get(1).status()).isEqualTo(TraceStatus.PICKED);
        assertThat(t.nodes().get(0).at()).isGreaterThan(t.nodes().get(1).at());
    }

    @Test
    @DisplayName("★★★ 失败返回（查无结果 / 手机号校验未过 / 无可用单量）→ empty，不编造推进")
    void failureBodyIsEmpty() {
        assertThat(Kuaidi100TraceProvider.parse(json, "SF", "X",
                "{\"result\":false,\"returnCode\":\"500\",\"message\":\"查询无结果，请隔段时间再查\"}")).isEmpty();
        assertThat(Kuaidi100TraceProvider.parse(json, "ZTO", "X",
                "{\"result\":false,\"returnCode\":\"408\",\"message\":\"快递公司参数异常：电话号码校验失败\"}")).isEmpty();
        assertThat(Kuaidi100TraceProvider.parse(json, "YTO", "X", "not json")).isEmpty();
        assertThat(Kuaidi100TraceProvider.parse(json, "YTO", "X", "")).isEmpty();
    }

    @Test
    @DisplayName("★★★ 时间多格式都认；都认不出时丢掉这个节点但不静默（真实响应未实跑，格式差一点轨迹就空）")
    void parsesSeveralTimeFormats() {
        assertThat(Kuaidi100TraceProvider.parseAt("2026-10-07 09:00:00", "")).isNotNull();
        assertThat(Kuaidi100TraceProvider.parseAt("", "2026/10/07 09:00:00")).isNotNull();
        assertThat(Kuaidi100TraceProvider.parseAt("2026-10-07T09:00:00", "")).isNotNull();
        assertThat(Kuaidi100TraceProvider.parseAt("2026-10-07 09:00", "")).isNotNull();
        // ftime 认不出时退回 time
        assertThat(Kuaidi100TraceProvider.parseAt("昨天 09:00", "2026-10-07 09:00:00")).isNotNull();
        assertThat(Kuaidi100TraceProvider.parseAt("", "")).isNull();
        assertThat(Kuaidi100TraceProvider.parseAt("昨天", "刚刚")).isNull();

        // 整条响应里时间全认不出 → 节点为空，但整单状态仍在（调用方据此不会倒退状态）
        var r = Kuaidi100TraceProvider.parse(json, "YTO", "YT1",
                "{\"message\":\"ok\",\"state\":\"3\",\"data\":[{\"ftime\":\"昨天\",\"time\":\"刚刚\",\"context\":\"已签收\"}]}");
        assertThat(r).isPresent();
        assertThat(r.get().nodes()).isEmpty();
        assertThat(r.get().status()).isEqualTo(TraceStatus.SIGNED);
    }

    @Test
    @DisplayName("★★ 状态映射：揽收/在途/派件/签收/疑难/退回/拒签，三位高级码取百位，认不出 → UNKNOWN")
    void statusMapping() {
        assertThat(Kuaidi100TraceProvider.statusOf("1")).isEqualTo(TraceStatus.PICKED);
        assertThat(Kuaidi100TraceProvider.statusOf("0")).isEqualTo(TraceStatus.IN_TRANSIT);
        // ★ 派件（含 501 投柜或驿站）从运输中拆出来（TDD-物流模块 批 1）
        assertThat(Kuaidi100TraceProvider.statusOf("5")).isEqualTo(TraceStatus.DELIVERING);
        assertThat(Kuaidi100TraceProvider.statusOf("501")).isEqualTo(TraceStatus.DELIVERING);
        assertThat(Kuaidi100TraceProvider.statusOf("1001")).as("到达派件城市仍是运输中").isEqualTo(TraceStatus.IN_TRANSIT);
        assertThat(Kuaidi100TraceProvider.statusOf("3")).isEqualTo(TraceStatus.SIGNED);
        assertThat(Kuaidi100TraceProvider.statusOf("301")).isEqualTo(TraceStatus.SIGNED);
        assertThat(Kuaidi100TraceProvider.statusOf("2")).isEqualTo(TraceStatus.EXCEPTION);
        assertThat(Kuaidi100TraceProvider.statusOf("6")).isEqualTo(TraceStatus.EXCEPTION);
        assertThat(Kuaidi100TraceProvider.statusOf("14")).isEqualTo(TraceStatus.EXCEPTION);
        assertThat(Kuaidi100TraceProvider.statusOf("9")).isEqualTo(TraceStatus.UNKNOWN);
        assertThat(Kuaidi100TraceProvider.statusOf("")).isEqualTo(TraceStatus.UNKNOWN);
    }

    @Test
    @DisplayName("★★ 承运商映射覆盖 ExpressCompanies 的每一家 —— 发货界面能选的，轨迹都查得到")
    void coversEveryKnownCarrier() {
        var p = new Kuaidi100TraceProvider("k", "c", null);
        for (var c : ExpressCompanies.all()) {
            assertThat(p.covers(c.code())).as("承运商 %s 没有快递100 公司码", c.code()).isTrue();
        }
        assertThat(p.covers("NOPE")).isFalse();
        assertThat(Kuaidi100TraceProvider.CODES.get("SF")).isEqualTo("shunfeng");
    }

    @Test
    @DisplayName("★★ 缺 key 或 customer → 不可用、不发请求；不认的承运商同样直接 empty")
    void unavailableWithoutCredentials() {
        assertThat(new Kuaidi100TraceProvider("k", "", null).available()).isFalse();
        assertThat(new Kuaidi100TraceProvider("", "c", null).available()).isFalse();
        assertThat(new Kuaidi100TraceProvider("", "", null).trace("SF", "SF1", "13800000000")).isEmpty();
        // 指向不可达地址：若真发了请求会走到异常分支，这里断言它在发请求之前就返回了
        assertThat(new Kuaidi100TraceProvider("k", "c", "http://127.0.0.1:9").trace("NOPE", "X", null)).isEmpty();
    }

    @Test
    @DisplayName("★★ 到柜只看最新节点：进过驿站又被取走签收的，不再亮「已到驿站」")
    void atLockerOnlyByNewestNode() {
        var locker = new TraceResult.TraceNode(2000L, TraceStatus.DELIVERING, "已存放至XX驿站", "深圳", null, null, "501");
        var signed = new TraceResult.TraceNode(3000L, TraceStatus.SIGNED, "已签收", "深圳", null, null, "301");
        assertThat(Kuaidi100TraceProvider.atLocker("501", java.util.List.of())).isTrue();
        assertThat(Kuaidi100TraceProvider.atLocker("5", java.util.List.of(locker))).isTrue();
        assertThat(Kuaidi100TraceProvider.atLocker("3", java.util.List.of(signed, locker)))
                .as("取件签收之后还亮着「已到驿站」，买家会再跑一趟").isFalse();
    }
}
