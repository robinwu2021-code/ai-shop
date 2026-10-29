package ai.neargo.shop.food.spi;

import ai.neargo.shop.food.dto.OfflineDraftContracts.Challenge;
import ai.neargo.shop.food.dto.OfflineDraftContracts.ChallengeRequest;
import ai.neargo.shop.food.dto.OfflineDraftContracts.Enroll;
import ai.neargo.shop.food.dto.OfflineDraftContracts.Lease;
import ai.neargo.shop.food.dto.OfflineDraftContracts.LeaseRequest;
import ai.neargo.shop.food.dto.OfflineDraftContracts.Receipt;
import ai.neargo.shop.food.dto.OfflineDraftContracts.Replay;
import ai.neargo.shop.food.dto.OfflineDraftContracts.Signer;
import ai.neargo.shop.food.dto.OfflineDraftContracts.Upload;

import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;

/** Private typed equivalent; original scope signature and service token remain mandatory. */
public interface OfflineDraftInternalApi {
    String ROOT = "/internal/food/offline-drafts";

    record ChallengeCommand(
            String entityNo, String storeNo, String deviceNo, ChallengeRequest request) {}

    record EnrollCommand(String entityNo, String storeNo, String deviceNo, Enroll request) {}

    record LeaseCommand(String entityNo, String storeNo, String deviceNo, LeaseRequest request) {}

    record CaptureCommand(String entityNo, String storeNo, String deviceNo, Upload request) {}

    record ReplayCommand(String entityNo, String storeNo, String deviceNo, Replay request) {}

    record StateCommand(
            String entityNo,
            String storeNo,
            String deviceNo,
            ai.neargo.shop.food.dto.CartAccess access) {}

    @PostExchange(ROOT + "/state")
    Lease state(@RequestBody StateCommand command);

    @PostExchange(ROOT + "/challenge")
    Challenge challenge(@RequestBody ChallengeCommand command);

    @PostExchange(ROOT + "/enroll")
    Signer enroll(@RequestBody EnrollCommand command);

    @PostExchange(ROOT + "/lease")
    Lease acquire(@RequestBody LeaseCommand command);

    @PostExchange(ROOT + "/capture")
    Receipt capture(@RequestBody CaptureCommand command);

    @PostExchange(ROOT + "/replay")
    Receipt replay(@RequestBody ReplayCommand command);
}
