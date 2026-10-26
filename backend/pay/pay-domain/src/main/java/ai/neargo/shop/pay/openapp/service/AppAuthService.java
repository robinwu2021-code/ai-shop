package ai.neargo.shop.pay.openapp.service;

import ai.neargo.shop.pay.access.PayCaller;
import ai.neargo.shop.pay.openapp.entity.PayApp;
import java.util.List;
import java.util.Map;

/**
 * 开放 API 的接入方鉴权与授权（安全规范 §2.3；TDD-支付服务-I5接入层 S3）。<b>纯数据校验，不读会话</b>，两种部署形态通用。
 */
public interface AppAuthService {

    /** 一次签名请求的全部输入（体只给哈希：过滤器已缓存原始字节并算好） */
    record SignedRequest(String appNo, String timestamp, String nonce, String signature, String method, String path,
                         Map<String, List<String>> query, String bodySha256Hex, String clientIp) {
    }

    /** @param failure 失败原因（只进日志与请求记录，<b>不回给调用方</b>）；成功为空 */
    record Result(PayApp app, String failure) {

        public boolean ok() {
            return app != null && failure == null;
        }
    }

    /** 校验顺序：app 存在且启用 → 环境（沙箱 / 生产）→ IP 白名单 → 时间窗 ±300s → 验签（逐把有效密钥）→ nonce 抢占 */
    Result authenticate(SignedRequest request);

    /** 按 AppId 取启用中的接入方；不存在或停用 → 未授权 */
    PayApp requireActive(String appNo);

    /** scope 不含 → {@code PAY_FORBIDDEN} */
    void requireScope(PayApp app, String scope);

    /**
     * 这个接入方做某个门店动作时的调用方：门店范围 = 授权里含该动作（COLLECT / REFUND / READ）的门店。
     * 代谁操作记 {@code APP:{appNo}} —— 退款要有操作人（I3），接入方代商户发起时操作人就是它。
     */
    PayCaller callerFor(PayApp app, String grantAction);

    /** 当日（UTC）该接入方已退 + 本笔 > {@code daily_refund_limit_minor} → {@code PAY_APP_REFUND_LIMIT}；限额 0 = 不允许退款 */
    void checkDailyRefund(PayApp app, long amountMinor);
}
