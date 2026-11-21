package ai.neargo.shop.invoice.gateway;

/**
 * 需要商家账号的加值中心通道（鲸跃等）：除了上传，还负责核验账号与配给字轨号段。
 *
 * <p>与 Turnkey 的区别：Turnkey 下号段由商家自己向国税局领、在 B 端登记；加值中心模式下号段由服务商配给，
 * 我们只负责取回入库 —— 入库后的取号与开立流程与手工登记的号段完全一样。
 */
public interface InvoiceProviderGateway extends InvoiceGateway {

    /** 用账号查服务商侧的营业人资料；账号无效时抛 {@link ProviderException}。 */
    ProviderOffice verify(ProviderAccount account);

    /**
     * 向服务商申请并取回一段号码。
     *
     * @param periodCode 期别，如 {@code 11512}（民国 115 年 11-12 月）
     */
    AllocatedRange allocate(ProviderAccount account, String periodCode);

    /**
     * @param qrKey 服务商回传的 QR 加密金钥原文（鲸跃 QRCode）；没给为空
     */
    record ProviderOffice(String taxId, String name, String qrKey) {}

    record AllocatedRange(String applyId, String track, int startNo, int endNo, String invoiceType) {}

    /** 服务商明确拒绝或不可达。{@link #code} 是服务商原文代码，原样带给用户便于对着服务商文档排查。 */
    class ProviderException extends RuntimeException {
        private final String code;

        public ProviderException(String code, Throwable cause) {
            super("invoice provider rejected: " + code, cause);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
