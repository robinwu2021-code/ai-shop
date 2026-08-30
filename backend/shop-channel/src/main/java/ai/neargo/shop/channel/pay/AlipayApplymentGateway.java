package ai.neargo.shop.channel.pay;

import ai.neargo.shop.channel.pay.base.AbstractApplymentGateway;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 支付宝直付通 · 二级商户入驻。
 *
 * <p>接口 {@link AlipayApis#SUB_MERCHANT_CREATE}（{@code ant.merchant.expand.indirect.zft.create}）。
 * 与微信一样是**异步**：提交拿到申请单号，结果稍后查。
 *
 * <p><b>字段形状与微信差得很远</b>，这正是把两家分成两个子类而不是一个类加 if 的理由：
 * 支付宝把商户类型叫 {@code merchant_type}（{@code 01} 企业 / {@code 02} 个体 / {@code 03} 个人），
 * 结算卡放在 {@code biz_cards} 数组里，而微信是 {@code account_info} 一个对象。
 * 写在一个类里，每加一个字段都要先想清楚「这个是谁的」。
 */
@Component
@ConditionalOnProperty(name = "shop.pay.alipay.enabled", havingValue = "true")
public class AlipayApplymentGateway extends AbstractApplymentGateway {

    public AlipayApplymentGateway(@Qualifier("alipayChannelClient") ChannelClient client) {
        super(client);
    }

    @Override
    public String payChannel() {
        return "ALIPAY";
    }

    @Override
    protected String submitApi() {
        return AlipayApis.SUB_MERCHANT_CREATE;
    }

    @Override
    protected String queryApi(String channelApplyNo) {
        // 直付通查询与创建是两个接口名，参数带申请单号 —— 由 ChannelClient 拼进业务参数
        return AlipayApis.SUB_MERCHANT_QUERY;
    }

    /**
     * 我方法律形态 → 支付宝商户类型。**只此一处**。
     *
     * <p>注意与微信的映射<b>方向相同但取值完全不同</b>：这里是数字码。
     * 两处各自写死自己的那一份，不共用一个「通用枚举」——
     * 共用的话，加第三家时要么改枚举（动到前两家），要么在枚举外再加映射（回到原点）。
     */
    static String merchantType(String legalForm) {
        return switch (legalForm == null ? "" : legalForm) {
            case "ENTERPRISE" -> "01";
            case "INDIVIDUAL" -> "02";
            case "MICRO" -> "03";
            default -> null;
        };
    }

    @Override
    protected Map<String, Object> buildSubmit(SubmitCommand cmd) {
        String type = merchantType(cmd.legalForm());
        if (type == null) {
            throw new ChannelClient.ChannelException(
                    "认不出的法律形态：" + cmd.legalForm() + "（不猜，猜错会按错误的商户类型入驻）", false);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("out_biz_no", cmd.entityNo());
        body.put("name", cmd.entityName());
        body.put("alias_name", cmd.entityName());
        body.put("merchant_type", type);
        body.put("contact_infos", java.util.List.of(new LinkedHashMap<>(Map.of(
                "name", nz(cmd.contactName()),
                "mobile", nz(cmd.contactPhone()),
                "type", "LEGAL_PERSON"))));
        // 结算卡：账户类型决定 account_type（个人 2 / 对公 1）
        body.put("biz_cards", java.util.List.of(new LinkedHashMap<>(Map.of(
                "account_no", nz(cmd.settleAccount()),
                "account_type", "MERCHANT_ID".equals(cmd.settleAccountType()) ? "1" : "2",
                "account_inst_name", "",
                "usage_type", "settle"))));
        if (cmd.licenses() != null && !cmd.licenses().isEmpty()) {
            body.put("business_license_pic", cmd.licenses().get(0));
        }
        return body;
    }

    @Override
    protected String applyNoOf(Map<String, Object> resp) {
        Object id = resp.get("order_id");
        return id == null ? null : String.valueOf(id);
    }

    /**
     * 直付通的申请状态：{@code 99} 完成 / {@code 03} 审核不通过 / 其余在途。
     *
     * <p><b>「审核通过但未签约」不算开好户</b> —— 与微信 NEED_SIGN 是同一类陷阱：
     * 状态看着像成功，而商家第一笔收款会失败。不在成功分支里的一律按在途处理。
     */
    @Override
    protected ApplymentResult parseResult(Map<String, Object> resp) {
        String status = String.valueOf(resp.get("apply_status"));
        return switch (status) {
            case "99" -> new ApplymentResult("ACTIVE", str(resp.get("sub_merchant_id")), null);
            case "03" -> new ApplymentResult("REJECTED", null, str(resp.get("reject_reason")));
            default -> new ApplymentResult("APPLYING", null, null);
        };
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
