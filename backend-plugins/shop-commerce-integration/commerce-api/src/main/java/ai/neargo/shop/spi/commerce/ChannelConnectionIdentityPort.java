package ai.neargo.shop.spi.commerce;

import java.time.Instant;

/** 由可信平台授权/查询适配器证明远端门店归属；不接受公共请求自报证明，不提供默认实现。 */
public interface ChannelConnectionIdentityPort {
    Proof verify(Identity identity);

    enum Status {
        VERIFIED,
        UNKNOWN,
        UNSUPPORTED
    }

    record Identity(
            String entityNo,
            String storeNo,
            String accountNo,
            String profileNo,
            String externalAccountRef,
            String externalStoreRef,
            String identityNamespace,
            boolean accountScoped,
            long accountVersion,
            long profileVersion) {}

    /** 原凭据所有者和原平台回执均须验证；VERIFIED必须精确绑定本次冻结身份及有效期。 */
    record Proof(
            Status status,
            Identity identity,
            String receiptRef,
            Instant verifiedAt,
            Instant validUntil) {}
}
