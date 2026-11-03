package ai.neargo.commerce.api.catalog;

import java.util.List;
import ai.neargo.commerce.api.common.*;

public record PublicationResult(String publicationNo, long catalogVersion, List<String> publishedVersionNos) {}
