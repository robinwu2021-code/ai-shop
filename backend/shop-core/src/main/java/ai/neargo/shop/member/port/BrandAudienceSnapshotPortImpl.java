package ai.neargo.shop.member.port;

import ai.neargo.shop.member.service.BrandAudienceSnapshotService;
import ai.neargo.shop.spi.member.BrandAudienceSnapshotPort;
import org.springframework.stereotype.Component;

/** Exports only member-owned frozen audience operations to other domains. */
@Component
public final class BrandAudienceSnapshotPortImpl implements BrandAudienceSnapshotPort {
    private final BrandAudienceSnapshotService service;

    public BrandAudienceSnapshotPortImpl(BrandAudienceSnapshotService service) {
        this.service = service;
    }

    @Override public Snapshot freeze(Context context, String resourceNo, String resourceVersion) {
        return service.freeze(context, resourceNo, resourceVersion);
    }

    @Override public Snapshot get(Context context, String snapshotNo, String resourceNo, String resourceVersion) {
        return service.get(context, snapshotNo, resourceNo, resourceVersion);
    }

    @Override public Snapshot claim(Context context, String snapshotNo, String resourceNo,
            String resourceVersion, String commandNo) {
        return service.claim(context, snapshotNo, resourceNo, resourceVersion, commandNo);
    }
}
