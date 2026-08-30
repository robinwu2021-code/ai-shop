package ai.neargo.shop.channel.pay;

import ai.neargo.shop.channel.pay.base.AbstractApplymentGateway;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 微信支付电商收付通 · 二级商户进件。
 *
 * <p>接口 {@link WechatApis#APPLYMENT}。**异步**：提交拿到 {@code applyment_id}，
 * 结果稍后由查询或回调给出。
 *
 * <p><b>主体类型的取值是通道定的，不是我们定的</b>：
 * 小微 {@code SUBJECT_TYPE_MICRO} / 个体 {@code SUBJECT_TYPE_INDIVIDUAL} /
 * 企业 {@code SUBJECT_TYPE_ENTERPRISE}。我方的 MICRO/INDIVIDUAL/ENTERPRISE
 * 在这里翻译一次 —— <b>这份映射只此一处</b>，散开写就会出现两个地方对不上，
 * 而对不上的表现是进件被拒，理由还是通道的原话，看不出是我们映射错了。
 *
 * <p><b>敏感字段按通道要求加密</b>：真实接入时结算账号等要用平台证书加密后再传
 * （{@code Wechatpay-Serial} 头指明用了哪张证书）。加密由 {@link ChannelClient}
 * 的实现负责 —— 这一层只管字段映射，不碰密钥。
 */
@Component
@ConditionalOnProperty(name = "shop.pay.wechat.enabled", havingValue = "true")
public class WechatApplymentGateway extends AbstractApplymentGateway {

    public WechatApplymentGateway(@Qualifier("wechatChannelClient") ChannelClient client) {
        super(client);
    }

    @Override
    public String payChannel() {
        return "WECHAT";
    }

    @Override
    protected String submitApi() {
        return WechatApis.APPLYMENT;
    }

    @Override
    protected String queryApi(String channelApplyNo) {
        return WechatApis.APPLYMENT + channelApplyNo;
    }

    /** 我方法律形态 → 微信主体类型。**只此一处**。 */
    static String subjectType(String legalForm) {
        return switch (legalForm == null ? "" : legalForm) {
            case "MICRO" -> "SUBJECT_TYPE_MICRO";
            case "INDIVIDUAL" -> "SUBJECT_TYPE_INDIVIDUAL";
            case "ENTERPRISE" -> "SUBJECT_TYPE_ENTERPRISE";
            // 认不出来**不猜**：猜小微会让一家企业按小微进件，额度与资料要求全错
            default -> null;
        };
    }

    /** 我方结算账户形态 → 微信账户类型。 */
    static String bankAccountType(String settleAccountType) {
        return "MERCHANT_ID".equals(settleAccountType) ? "BANK_ACCOUNT_TYPE_CORPORATE"
                : "BANK_ACCOUNT_TYPE_PERSONAL";
    }

    @Override
    protected Map<String, Object> buildSubmit(SubmitCommand cmd) {
        String subject = subjectType(cmd.legalForm());
        if (subject == null) {
            throw new ChannelClient.ChannelException(
                    "认不出的法律形态：" + cmd.legalForm() + "（不猜，猜错会按错误的主体类型进件）", false);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("out_request_no", cmd.entityNo());
        body.put("organization_type", subject);
        Map<String, Object> subjectInfo = new LinkedHashMap<>();
        subjectInfo.put("merchant_name", cmd.entityName());
        subjectInfo.put("business_license_info", Map.of("license_copy",
                cmd.licenses() == null || cmd.licenses().isEmpty() ? "" : cmd.licenses().get(0)));
        body.put("subject_info", subjectInfo);
        Map<String, Object> contact = new LinkedHashMap<>();
        contact.put("contact_name", cmd.contactName());
        contact.put("mobile_phone", cmd.contactPhone());
        body.put("contact_info", contact);
        Map<String, Object> settle = new LinkedHashMap<>();
        settle.put("bank_account_type", bankAccountType(cmd.settleAccountType()));
        settle.put("account_number", cmd.settleAccount());
        settle.put("account_name", cmd.entityName());
        body.put("account_info", settle);
        return body;
    }

    @Override
    protected String applyNoOf(Map<String, Object> resp) {
        Object id = resp.get("applyment_id");
        return id == null ? null : String.valueOf(id);
    }

    /**
     * 微信的申请状态字段是 {@code applyment_state}。
     *
     * <p><b>只有 {@code APPLYMENT_STATE_FINISHED} 才算开好户</b> ——
     * {@code NEED_SIGN}（待签约）看起来像成功，实际商家还收不了钱；
     * 当成 ACTIVE 的话，页面会说「可以收款了」而第一笔就失败。
     */
    @Override
    protected ApplymentResult parseResult(Map<String, Object> resp) {
        String state = String.valueOf(resp.get("applyment_state"));
        return switch (state) {
            case "APPLYMENT_STATE_FINISHED" ->
                    new ApplymentResult("ACTIVE", str(resp.get("sub_mchid")), null);
            case "APPLYMENT_STATE_REJECTED" ->
                    new ApplymentResult("REJECTED", null,
                            // 驳回原文原样带回，商家页面直接展示
                            str(resp.getOrDefault("audit_detail", resp.get("applyment_state_desc"))));
            default -> new ApplymentResult("APPLYING", null, null);
        };
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
