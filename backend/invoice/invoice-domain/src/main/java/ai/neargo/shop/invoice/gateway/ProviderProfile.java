package ai.neargo.shop.invoice.gateway;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 供应商的声明（TDD-发票模块 §19）：服务哪些税制、号码从哪来、报文怎么送达、要不要商家账号。
 *
 * <p>编号方式与上报方式是<b>声明</b>，不进继承树：同一种对接形态里既有服务商配号段的（鲸跃），也有开立时在线给号的（绿界）。
 *
 * <p>{@code regimes} 只是声明，供列出候选供应商用；供应商层与开立流程都不据此拦截 —— 用哪家由系统统一决定。
 *
 * <p>TDD-发票-供应商能力定义完善：供应商另外<b>声明</b>收哪些上传报文、支持哪些业务特性、凭据字段怎么填、
 * QR 金钥从哪来、能领哪些期别的号段。领域与页面按声明行事；四参数构造给没有专门声明的供应商（测试桩、样例）用默认值。
 *
 * @param regimes       服务的税制编码（{@code TW_EGUI}、{@code AE_EINVOICE} …）；{@value #ANY_REGIME} 表示不限（测试桩）
 * @param uploads       收哪些上传报文；不在其中的报文上传直接判终态、预检不通过
 * @param features      支持的业务特性（页面说明用；能否送出以预检为准）
 * @param credentials   凭据字段；不要账号的供应商为空
 * @param qrKeySource   QR 加密金钥来源
 * @param rangeWindows  能配给号段的期别窗口（只对服务商配号有意义）
 */
public record ProviderProfile(Set<String> regimes, NumberingMode numbering, ReportMode reporting,
        boolean requiresAccount, Set<ProviderOperation> uploads, Set<InvoiceFeature> features,
        List<CredentialField> credentials, QrKeySource qrKeySource, Set<RangeWindow> rangeWindows) {

    public static final String ANY_REGIME = "*";

    /** 台湾加值中心与 Turnkey 都收的四类上传报文。 */
    public static final Set<ProviderOperation> STANDARD_UPLOADS = Set.of(ProviderOperation.ISSUE, ProviderOperation.VOID,
            ProviderOperation.ALLOWANCE, ProviderOperation.ALLOWANCE_VOID);

    public ProviderProfile {
        if (regimes == null || regimes.isEmpty()) throw new IllegalArgumentException("provider must serve a regime");
        regimes = Set.copyOf(regimes);
        uploads = uploads == null ? STANDARD_UPLOADS : Set.copyOf(uploads);
        features = features == null ? Set.copyOf(EnumSet.allOf(InvoiceFeature.class)) : Set.copyOf(features);
        credentials = credentials == null ? (requiresAccount ? CredentialField.GENERIC : List.of()) : List.copyOf(credentials);
        qrKeySource = qrKeySource == null ? QrKeySource.MERCHANT : qrKeySource;
        rangeWindows = rangeWindows == null || rangeWindows.isEmpty() ? Set.of(RangeWindow.ANY) : Set.copyOf(rangeWindows);
    }

    /** 没有专门声明时的默认值：收四类标准报文、不限特性、通用凭据三项、商家手填 QR 金钥、任意期别。 */
    public ProviderProfile(Set<String> regimes, NumberingMode numbering, ReportMode reporting, boolean requiresAccount) {
        this(regimes, numbering, reporting, requiresAccount, null, null, null, null, null);
    }

    public ProviderProfile withUploads(Set<ProviderOperation> value) {
        return new ProviderProfile(regimes, numbering, reporting, requiresAccount, value, features, credentials,
                qrKeySource, rangeWindows);
    }

    public ProviderProfile withFeatures(Set<InvoiceFeature> value) {
        return new ProviderProfile(regimes, numbering, reporting, requiresAccount, uploads, value, credentials,
                qrKeySource, rangeWindows);
    }

    public ProviderProfile withCredentials(List<CredentialField> value) {
        return new ProviderProfile(regimes, numbering, reporting, requiresAccount, uploads, features, value,
                qrKeySource, rangeWindows);
    }

    public ProviderProfile withQrKeySource(QrKeySource value) {
        return new ProviderProfile(regimes, numbering, reporting, requiresAccount, uploads, features, credentials,
                value, rangeWindows);
    }

    public ProviderProfile withRangeWindows(Set<RangeWindow> value) {
        return new ProviderProfile(regimes, numbering, reporting, requiresAccount, uploads, features, credentials,
                qrKeySource, value);
    }

    /** 收不收这类上传报文。 */
    public boolean accepts(ProviderOperation op) {
        return op != null && uploads.contains(op);
    }

    public boolean supports(InvoiceFeature feature) {
        return features.contains(feature);
    }

    /** 这个期别窗口能不能向服务商领号段。 */
    public boolean allocatable(RangeWindow window) {
        return rangeWindows.contains(RangeWindow.ANY) || rangeWindows.contains(window);
    }

    /** 某个凭据位的声明；没有返回 null。 */
    public CredentialField credential(CredentialField.Slot slot) {
        return credentials.stream().filter(f -> f.slot() == slot).findFirst().orElse(null);
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
