package ai.neargo.shop.reportbridge.service;

import java.util.List;

/** Server-only opaque private artifacts; never returns a signed/public URL. */
public interface ReportArtifactStore {
    void put(String key,byte[] content);
    byte[] read(String key);
    void delete(List<String> keys);
}
