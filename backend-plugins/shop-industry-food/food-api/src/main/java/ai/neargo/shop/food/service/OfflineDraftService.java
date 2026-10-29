package ai.neargo.shop.food.service;

import ai.neargo.shop.food.dto.CartAccess;
import ai.neargo.shop.food.dto.OfflineDraftContracts.Challenge;
import ai.neargo.shop.food.dto.OfflineDraftContracts.ChallengeRequest;
import ai.neargo.shop.food.dto.OfflineDraftContracts.Enroll;
import ai.neargo.shop.food.dto.OfflineDraftContracts.Lease;
import ai.neargo.shop.food.dto.OfflineDraftContracts.LeaseRequest;
import ai.neargo.shop.food.dto.OfflineDraftContracts.Receipt;
import ai.neargo.shop.food.dto.OfflineDraftContracts.Replay;
import ai.neargo.shop.food.dto.OfflineDraftContracts.Signer;
import ai.neargo.shop.food.dto.OfflineDraftContracts.Upload;

/** Original-device nonpayment capture and explicit authenticated online draft replay. */
public interface OfflineDraftService {
    Challenge challenge(String entity, String store, String device, ChallengeRequest request);

    Signer enroll(String entity, String store, String device, Enroll request);

    Lease state(String entity, String store, String device, CartAccess access);

    Lease acquire(String entity, String store, String device, LeaseRequest request);

    Receipt capture(String entity, String store, String device, Upload request);

    Receipt replay(String entity, String store, String device, Replay request);
}
