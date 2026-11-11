package ai.neargo.shop.member.service;

import ai.neargo.shop.spi.member.BrandAudienceSnapshotPort.Context;
import ai.neargo.shop.spi.member.BrandAudienceSnapshotPort.Snapshot;

/** Member-owned frozen-audience operations, without reselecting recipients during recovery. */
public interface BrandAudienceSnapshotService {
    Snapshot freeze(Context context, String resourceNo, String resourceVersion);
    Snapshot get(Context context, String snapshotNo, String resourceNo, String resourceVersion);
    Snapshot claim(Context context, String snapshotNo, String resourceNo, String resourceVersion, String commandNo);
}
