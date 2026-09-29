package ai.neargo.shop.elec.support;

import ai.neargo.shop.elec.gateway.ElecAlerts.RfqAlert;
import ai.neargo.shop.elec.gateway.ElecAlerts.RfqLine;
import ai.neargo.shop.elec.gateway.ElecAlerts.Source;
import ai.neargo.shop.elec.gateway.ElecAlerts.SupplierAlert;

import java.math.BigDecimal;

/**
 * 企业微信群消息的正文（markdown）。纯字符串，不发送 —— 发送在 elec-svc。
 *
 * <p><b>手机号不掩码</b>：第一步没有运营端之外的地方能查到号码，群里掩了码这条消息就没法用。
 * 群成员就是平台运营。
 */
public final class AlertText {

    /** 企业微信 markdown 上限 4096 字节；行数多的 BOM 只列前面这些 */
    static final int MAX_LINES = 12;

    /** 每行最多列几家有货的 —— 列全了消息超长，运营要的是「先打谁」 */
    static final int MAX_SOURCES = 3;

    private AlertText() {
    }

    public static String supplier(SupplierAlert s) {
        return "**新的元器件供应商**\n"
                + "> 公司：" + nvl(s.companyName(), "还没填") + '\n'
                + "> 类型：" + kind(s.kind()) + '\n'
                + "> 城市：" + nvl(s.city(), "未填") + '\n'
                + "> 联系人：" + nvl(s.contactName(), "未填") + "　" + s.contactPhone() + '\n'
                + "> 编号：" + s.supplierNo() + '\n'
                + "点一下就成为供应商，入驻即可上传库存";
    }

    public static String rfq(RfqAlert rfq) {
        StringBuilder md = new StringBuilder()
                .append("**新的元器件询价** ").append(rfq.rfqNo()).append('\n')
                .append("> 联系人：").append(nvl(rfq.contactName(), "未填")).append("　")
                .append(rfq.contactPhone()).append('\n');
        if (rfq.company() != null && !rfq.company().isBlank()) {
            md.append("> 公司：").append(rfq.company()).append('\n');
        }
        md.append("> 发票：").append(invoice(rfq.needInvoice()))
                .append("　批次：").append(dc(rfq.dcReq()))
                .append("　收货：").append(nvl(rfq.deliverCity(), "未填")).append('\n');
        if (rfq.remark() != null && !rfq.remark().isBlank()) {
            md.append("> 备注：").append(rfq.remark()).append('\n');
        }
        int shown = 0;
        for (RfqLine line : rfq.lines()) {
            if (shown == MAX_LINES) {
                md.append("\n还有 ").append(rfq.lines().size() - shown).append(" 行没列出");
                break;
            }
            shown++;
            md.append('\n').append(shown).append(". **").append(line.mpn()).append("**");
            if (line.mfr() != null && !line.mfr().isBlank()) {
                md.append(" (").append(line.mfr()).append(')');
            }
            md.append(" × ").append(line.qty());
            if (line.targetE6() != null) {
                md.append("　目标 ¥").append(yuan(line.targetE6()));
            }
            if (line.sources().isEmpty()) {
                md.append("\n<font color=\"warning\">库里没货，要找货</font>");
            }
            int n = 0;
            for (Source src : line.sources()) {
                if (n++ == MAX_SOURCES) {
                    md.append("\n　…另有 ").append(line.sources().size() - MAX_SOURCES).append(" 家");
                    break;
                }
                md.append("\n　").append(nvl(src.companyName(), "未填公司名")).append(' ')
                        .append(src.contactPhone()).append('：').append(src.qty());
                if (src.dateCode() != null) {
                    md.append(" DC").append(src.dateCode());
                }
                if (src.priceE6() != null) {
                    md.append(" ¥").append(yuan(src.priceE6())).append(src.taxIncluded() ? "含税" : "未税");
                }
            }
        }
        return md.toString();
    }

    public static String accepted(String rfqNo, String contactName, String contactPhone, String summary) {
        return "**买家接受了报价** " + rfqNo + '\n'
                + "> " + summary + '\n'
                + "> 联系人：" + nvl(contactName, "未填") + "　" + contactPhone + '\n'
                + "去签合同、安排发货";
    }

    /** 百万分之一元 → 元，去掉尾零（¥6.2、¥0.0015） */
    public static String yuan(long e6) {
        return BigDecimal.valueOf(e6, 6).stripTrailingZeros().toPlainString();
    }

    private static String kind(String kind) {
        return switch (nvl(kind, "")) {
            case "AGENT" -> "代理商";
            case "TRADER" -> "贸易商";
            case "FACTORY" -> "工厂余料";
            default -> "其他";
        };
    }

    private static String invoice(String v) {
        return switch (nvl(v, "")) {
            case "VAT_SPECIAL" -> "专票";
            case "VAT_NORMAL" -> "普票";
            default -> "不开票";
        };
    }

    private static String dc(String v) {
        return switch (nvl(v, "")) {
            case "Y1" -> "一年内";
            case "Y2" -> "两年内";
            default -> "不限";
        };
    }

    private static String nvl(String v, String def) {
        return v == null || v.isBlank() ? def : v;
    }
}
