package ai.neargo.shop.report.service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import ai.neargo.shop.report.dto.ReportRows.CursorRow;
import ai.neargo.shop.report.dto.ReportRows.DirtyRow;
import ai.neargo.shop.spi.report.ReportContracts.BuildLease;
import ai.neargo.shop.spi.report.ReportContracts.BuildReq;
import ai.neargo.shop.spi.report.ReportContracts.BuildProblem;
import ai.neargo.shop.spi.report.ReportContracts.MetricFact;
import ai.neargo.shop.spi.report.ReportContracts.Partition;
import ai.neargo.shop.spi.report.ReportContracts.Publication;
import ai.neargo.shop.spi.report.ReportContracts.PublishedDay;
import ai.neargo.shop.spi.report.ReportContracts.Scope;
import ai.neargo.shop.spi.report.ReportContracts.SourceSnapshot;

public interface ReportStorageService {
    List<BuildProblem> buildProblems(Scope scope,LocalDate from,LocalDate to,int version,int limit);
    List<CursorRow> enabledScopesForWorker(int limit);
    List<BuildReq> recoverableBuilds(Scope scope, int version, int limit);
    void enableScope(Scope scope, int version, LocalDate firstDate);
    List<CursorRow> scopes(Scope scope);
    BuildLease beginBuild(BuildReq request, String worker);
    void stage(BuildLease lease, List<MetricFact> facts, List<SourceSnapshot> sources);
    Publication publish(BuildLease lease);
    Optional<Publication> findResult(Scope scope, String requestKey);
    void fail(BuildLease lease, String errorCode);
    List<PublishedDay> readDays(Scope scope, LocalDate from, LocalDate to, int version);
    void markDirty(Partition partition, String eventRef, String reason, Instant readyAfter);
    List<DirtyRow> pendingDirty(Scope scope, int version, int limit);
    void activateVersion(Scope scope, int version, LocalDate from, LocalDate to);
}
