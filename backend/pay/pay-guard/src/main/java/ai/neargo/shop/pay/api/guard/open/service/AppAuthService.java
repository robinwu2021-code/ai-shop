package ai.neargo.shop.pay.api.guard.open.service;

import ai.neargo.shop.pay.access.PayCaller;
import ai.neargo.shop.pay.openapp.entity.PayApp;
import java.util.List;
import java.util.Map;

/**
 * 开放 API 的接入方鉴权与授权（安全规范 §2.3；TDD-支付服务-I5接入层 S3）。<b>纯数据校验，不读会话</b>，两种部署形态通用。
 */
public interface AppAuthService {

    /** 一次签名请求的全部输入（体只给哈希：过滤器已缓存原始字节并算好） */
    /** @param keySerial 请求头 {@code X-Key-Serial}（推荐带）：指定签名用的密钥版本；不带则逐把试（开发计划 M3.2） */
    record SignedRequest(String appNo, String timestamp, String nonce, String signature, String keySerial, String method,
                         String path, Map<String, List<String>> query, String bodySha256Hex, String clientIp) {
    }

    /**
     * 失败的种类（类清单 §3 {@code AuthFailure}；开发计划 M3.7）：守卫据此回错误码。
     * 只有 {@link #TIMESTAMP} / {@link #REPLAYED} / {@link #IP} 对外可分 —— 其余统一 401，不给探测器递线索。
     */
    enum AuthFailure {
        /** 开放 API 未开启、缺签名头、nonce 格式不对 */
        REQUEST,
        /** AppId 不存在或停用 */
        APP,
        /** 沙箱 app 打生产、生产 app 打沙箱 */
        ENV,
        /** 来源 IP 不在接入方白名单 */
        IP,
        /** 时间戳不是毫秒数或在窗口外 */
        TIMESTAMP,
        /** 签名不符（含密钥已吊销） */
        SIGNATURE,
        /** nonce 重放 */
        REPLAYED
    }

    /**
     * @param kind    失败种类；成功为空
     * @param failure    失败原因（只进日志与请求记录，<b>不回给调用方</b>）；成功为空
     * @param keyVersion 验签命中的密钥版本（{@code pay_api_request_log.sign_key_version}）；部署配置里的密钥没有版本，为空
     */
    record Result(PayApp app, AuthFailure kind, String failure, Integer keyVersion) {

        public static Result ok(PayApp app, Integer keyVersion) {
            return new Result(app, null, null, keyVersion);
        }

        public static Result failed(AuthFailure kind, String failure) {
            return new Result(null, kind, failure, null);
        }

        public boolean ok() {
            return app != null && kind == null;
        }
    }

    /** 校验顺序：app 存在且启用 → 环境（沙箱 / 生产）→ IP 白名单 → 时间窗 ±300s → 验签（逐把有效密钥）→ nonce 抢占 */
    Result authenticate(SignedRequest request);

    /** 按 AppId 取启用中的接入方；不存在或停用 → 未授权 */
    PayApp requireActive(String appNo);

    /**
     * 这个接入方做某个门店动作时的调用方：门店范围 = 授权里含该动作（COLLECT / REFUND / READ）的门店。
     * 代谁操作记 {@code APP:{appNo}} —— 退款要有操作人（I3），接入方代商户发起时操作人就是它。
     */
    PayCaller callerFor(PayApp app, String grantAction);

    /** 当日（UTC）该接入方已退 + 本笔 > {@code daily_refund_limit_minor} → {@code PAY_APP_REFUND_LIMIT}；限额 0 = 不允许退款 */
    void checkDailyRefund(PayApp app, long amountMinor);

    /** 同上，按 {@code pay_app.id}（即开放 API 调用方的 {@code callerId}）现查接入方 —— controller 手里只有 {@code PayCaller} */
    void checkDailyRefund(Long payAppId, long amountMinor);
}
