package ai.neargo.shop.commerce.channel;

import ai.neargo.shop.commerce.dto.SourceNoticeContracts.Notice;
import ai.neargo.shop.commerce.dto.SourceNoticeContracts.Receipt;
import ai.neargo.shop.commerce.service.SourceNoticeService;
import ai.neargo.shop.spi.commerce.SourceNoticeInternalApi;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.ServiceCalls;

import java.util.function.Supplier;

/** A remote producer waits for durable acceptance; only the CIM owner may run recovery. */
public final class RemoteSourceNoticeService implements SourceNoticeService {
    private final Supplier<SourceNoticeInternalApi> api;

    public RemoteSourceNoticeService(Supplier<SourceNoticeInternalApi> api) {
        this.api = api;
    }

    @Override
    public Receipt receive(Notice notice) {
        var receipt = ServiceCalls.call(ServiceName.COMMERCE, () -> api.get().receive(notice));
        if (receipt == null
                || receipt.noticeNo() == null
                || receipt.noticeNo().isBlank()
                || receipt.readyAt() == null)
            throw new IllegalStateException("Missing durable source notice receipt");
        return receipt;
    }

    @Override
    public int recoverDue(int limit) {
        throw new UnsupportedOperationException("Only the CIM owner can recover source notices");
    }
}
