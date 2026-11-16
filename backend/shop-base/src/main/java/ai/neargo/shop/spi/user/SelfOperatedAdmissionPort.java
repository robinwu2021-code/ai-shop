package ai.neargo.shop.spi.user;

import ai.neargo.shop.common.PageData;

/** Platform admission management reads platform-owned entities without crossing table ownership. */
public interface SelfOperatedAdmissionPort {
    PageData<Entry> search(String market, String status, String keyword, long limit);

    record Entry(String merchantNo, String name, String ownerUserNo, String status,
                 String serviceScope, long createdAt) {}
}
