package ai.neargo.shop.invoice.gateway;

import java.util.Set;

/**
 * 供应商的声明（TDD-发票模块 §19）：服务哪些税制、号码从哪来、报文怎么送达、要不要商家账号。
 *
 * <p>编号方式与上报方式是<b>声明</b>，不进继承树：同一种对接形态里既有服务商配号段的（鲸跃），也有开立时在线给号的（绿界）。
 *
 * <p>{@code regimes} 只是声明，供列出候选供应商用；供应商层与开立流程都不据此拦截 —— 用哪家由系统统一决定。
 *
 * @param regimes 服务的税制编码（{@code TW_EGUI}、{@code AE_EINVOICE} …）；{@value #ANY_REGIME} 表示不限（测试桩）
 */
public record ProviderProfile(Set<String> regimes, NumberingMode numbering, ReportMode reporting,
        boolean requiresAccount) {

    public static final String ANY_REGIME = "*";

    public ProviderProfile {
        if (regimes == null || regimes.isEmpty()) throw new IllegalArgumentException("provider must serve a regime");
        regimes = Set.copyOf(regimes);
    }

    public boolean supports(String regimeCode) {
        return regimes.contains(ANY_REGIME) || regimes.contains(regimeCode);
    }

    /** 发票号码从哪来。 */
    public enum NumberingMode {
        /** 商家自领号段、在本系统登记（Turnkey、测试桩） */
        LOCAL_RANGE,
        /** 服务商配给号段，本系统取回入库（鲸跃） */
        PROVIDER_RANGE,
        /** 开立时服务商在线给号（绿界、蓝新、光贸） */
        PROVIDER_ONLINE,
        /** 营业人自编唯一连续号，不需要号段（阿联酋 Peppol） */
        LOCAL_SEQUENCE;

        /** 号码出自 {@code ivc_number_range}，开立主流程目前只支持这两种。 */
        public boolean usesRanges() {
            return this == LOCAL_RANGE || this == PROVIDER_RANGE;
        }
    }

    /** 报文怎么送到税务机关。 */
    public enum ReportMode {
        /** 开立后异步上传，送达即算（台湾加值中心） */
        ASYNC_UPLOAD,
        /** 落文件交给对方程序，结果看回执目录（Turnkey） */
        FILE_DROP,
        /** 交给接入点服务商在网络里投递，结果异步回执（阿联酋 Peppol 五角模型） */
        NETWORK_DELIVERY,
        /** 开立请求本身即上报（在线给号类） */
        SYNC_ON_ISSUE
    }
}
