package ai.neargo.shop.food.foodclient;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.dto.FoodStationRuntimeContracts.CommandVO;
import ai.neargo.shop.food.dto.FoodStationRuntimeContracts.ControlReq;
import ai.neargo.shop.food.dto.FoodStationRuntimeContracts.StateVO;
import ai.neargo.shop.food.dto.FoodStationRuntimeContracts.VoiceClaim;
import ai.neargo.shop.food.dto.FoodStationRuntimeContracts.VoiceReceipt;
import ai.neargo.shop.food.service.FoodStationRuntimeService;
import ai.neargo.shop.food.spi.FoodStationRuntimeInternalApi;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.ServiceCallException;
import ai.neargo.svc.client.ServiceCalls;

import java.util.Set;
import java.util.function.Supplier;

/**
 * Keeps the RemoteFoodStationRuntimeService business boundary scoped to original restaurant facts
 * and lifecycle authority.
 */
public class RemoteFoodStationRuntimeService implements FoodStationRuntimeService {
    private final FoodStationRuntimeInternalApi api;

    public RemoteFoodStationRuntimeService(FoodStationRuntimeInternalApi api) {
        this.api = api;
    }

    @Override
    public StateVO state(String e, String s, String station) {
        var result = call(() -> api.state(new FoodStationRuntimeInternalApi.Scope(e, s, station)));
        if (result == null
                || !station.equals(result.stationNo())
                || result.version() < 0
                || !Set.of("RUNNING", "PAUSED").contains(result.status())) {
            bad();
        }
        return result;
    }

    @Override
    public void requireRunning(String e, String s, String station) {
        if (!Boolean.TRUE.equals(
                call(
                        () ->
                                api.requireRunning(
                                        new FoodStationRuntimeInternalApi.Scope(e, s, station))))) {
            bad();
        }
    }

    @Override
    public CommandVO control(
            String e, String s, String a, String station, ControlReq req, String key) {
        var result =
                valid(
                        call(
                                () ->
                                        api.control(
                                                new FoodStationRuntimeInternalApi.Control(
                                                        e, s, a, station, req, key))));
        if (!station.equals(result.stationNo())
                || !req.action().equals(result.action())
                || !req.reason().equals(result.reason())) {
            bad();
        }
        return result;
    }

    @Override
    public VoiceClaim claim(String e, String s, String a, String subscription) {
        var result =
                call(
                        () ->
                                api.claim(
                                        new FoodStationRuntimeInternalApi.Poll(
                                                e, s, a, subscription)));
        if (result == null) {
            bad();
        }
        var voice = result.claim();
        if (voice != null
                && (voice.commandNo() == null
                        || voice.deviceNo() == null
                        || voice.stationNo() == null
                        || voice.voiceText() == null
                        || voice.leaseToken() == null
                        || voice.leaseUntil() == null)) {
            bad();
        }
        return voice;
    }

    @Override
    public CommandVO acknowledge(
            String e, String s, String a, String subscription, VoiceReceipt receipt) {
        var result =
                valid(
                        call(
                                () ->
                                        api.acknowledge(
                                                new FoodStationRuntimeInternalApi.Receipt(
                                                        e, s, a, subscription, receipt))));
        if (!receipt.commandNo().equals(result.commandNo())
                || !receipt.status().equals(result.status())) {
            bad();
        }
        return result;
    }

    private CommandVO valid(CommandVO result) {
        if (result == null
                || result.commandNo() == null
                || result.stationNo() == null
                || result.sourceVersion() < 0
                || !Set.of("APPLIED", "QUEUED", "SENT", "PLAYED", "NOT_PLAYED", "UNKNOWN")
                        .contains(result.status())) {
            bad();
        }
        return result;
    }

    private <T> T call(Supplier<T> action) {
        try {
            return ServiceCalls.call(ServiceName.FOOD, action);
        } catch (ServiceCallException failure) {
            ErrorCode code =
                    switch (failure.statusCode()) {
                        case 400 -> ErrorCode.BAD_REQUEST;
                        case 403 -> ErrorCode.FORBIDDEN;
                        case 404 -> ErrorCode.NOT_FOUND;
                        case 409 -> ErrorCode.CONFLICT;
                        default -> null;
                    };
            if (code != null) {
                throw BizException.of(code);
            }
            throw failure;
        }
    }

    private static void bad() {
        throw BizException.of(ErrorCode.CONFLICT);
    }
}
