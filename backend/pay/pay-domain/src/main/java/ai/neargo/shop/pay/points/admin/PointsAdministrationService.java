package ai.neargo.shop.pay.points.admin;

import ai.neargo.shop.pay.points.model.AgreementSnapshot;
import ai.neargo.shop.pay.points.model.PointType;
import ai.neargo.shop.pay.points.pool.PointsSwitchService.Flow;
import ai.neargo.shop.pay.points.pool.PointsSwitchService.Scope;
import ai.neargo.shop.pay.points.type.PointTypeService.CreateType;
import ai.neargo.shop.spi.points.PointsEarnPort.RuleMode;

import java.util.List;

/**
 * Configuration commands are audited and replayable, without manufacturing source or money facts.
 */
public interface PointsAdministrationService {
    List<PointType> types();

    PointType orderDefault(String market);

    ChangeResult create(Create request, String actor);

    ChangeResult transition(TypeChange request, String actor);

    ChangeResult rename(TypeRename request, String actor);

    FallbackRule fallback(String typeCode);

    ChangeResult saveFallback(FallbackChange request, String actor);

    SwitchPage switches(String typeCode, long after, int limit);

    ChangeResult changeSwitch(SwitchChange request, String actor);

    Terms terms(String typeCode);

    ChangeResult publishTerms(TermsChange request, String actor);

    AgreementPage agreements(String typeCode, String storeNo, long after, int limit);

    record Terms(String version, String hash, String text) {}

    enum TypeAction {
        ACTIVATE,
        DRAIN,
        RETIRE,
        PAUSE_EARNING,
        RESUME_EARNING,
        SET_DEFAULT
    }

    record Create(String requestId, CreateType command, String reason) {}

    record TypeChange(String requestId, String typeCode, TypeAction action, String reason) {}

    record TypeRename(String requestId, String typeCode, String expectedName, String name, String reason) {}

    record FallbackRule(long revision, RuleMode mode, long value) {}

    record FallbackChange(
            String requestId,
            String typeCode,
            long expectedRevision,
            RuleMode mode,
            long value,
            String reason) {}

    record SwitchChange(
            String requestId,
            Scope scope,
            String typeCode,
            String subjectNo,
            boolean enabled,
            Flow flow,
            String reason) {
        public SwitchChange {
            if (flow == null) {
                flow = Flow.BOTH;
            }
        }
    }

    record TermsChange(
            String requestId, String typeCode, String version, String text, String reason) {}

    record ChangeResult(String requestId, String action, String typeCode, long at) {}

    record SwitchView(
            long id,
            Scope scope,
            String typeCode,
            String subjectNo,
            boolean enabled,
            Flow flow,
            String actor,
            String reason,
            long at) {}

    record SwitchPage(List<SwitchView> rows, long next, boolean hasMore) {
        public SwitchPage {
            rows = List.copyOf(rows);
        }
    }

    record AgreementView(long id, AgreementSnapshot agreement, String state, long version) {}

    record AgreementPage(List<AgreementView> rows, long next, boolean hasMore) {
        public AgreementPage {
            rows = List.copyOf(rows);
        }
    }
}
