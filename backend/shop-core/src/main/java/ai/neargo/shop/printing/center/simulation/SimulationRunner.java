package ai.neargo.shop.printing.center.simulation;

import static ai.neargo.shop.printing.center.simulation.SimulationModels.*;
import static ai.neargo.shop.printing.center.support.PrintSupport.*;

import ai.neargo.shop.print.api.*;
import ai.neargo.shop.print.api.dto.PrintConfigDtos.*;
import ai.neargo.shop.print.api.dto.PrintJobDtos.*;
import ai.neargo.shop.print.api.dto.PrintSimulationDtos.*;
import ai.neargo.shop.printing.center.dispatch.PrintStateModels.*;
import ai.neargo.shop.printing.center.dispatch.PrintStatePolicy;
import ai.neargo.shop.printing.center.layout.*;
import ai.neargo.shop.printing.center.persistence.entity.*;
import ai.neargo.shop.printing.center.persistence.mapper.PrintMappers.*;
import ai.neargo.shop.printing.center.support.*;
import ai.neargo.shop.printing.center.template.*;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.*;
import java.util.*;

@Service
public class SimulationRunner {
    private final SimulatedDispatch simulated;
    private final PrnSimulationRunMapper simulations;
    private final SimulationFixtures fixtures;
    private final PrintStatePolicy policy;
    private final PrintJsonCodec json;
    private final TransactionTemplate tx;

    public SimulationRunner(
            PrnSimulationRunMapper s,
            SimulationFixtures f,
            PrintStatePolicy p,
            PrintJsonCodec j,
            @Qualifier("transactionManager") PlatformTransactionManager manager,
            SimulatedDispatch simulated) {
        this.simulated = simulated;
        simulations = s;
        fixtures = f;
        policy = p;
        json = j;
        tx = new TransactionTemplate(manager);
    }

    public PrintWorkReport runNext(PrintWorkerBudget budget) {
        int done = 0, failed = 0;
        long end = System.nanoTime() + budget.timeBudget().toNanos();
        while (done + failed < budget.maxItems() && System.nanoTime() < end) {
            Boolean result =
                    tx.execute(
                            status -> {
                                var r =
                                        simulations.selectOne(
                                                new QueryWrapper<PrnSimulationRun>()
                                                        .and(
                                                                q ->
                                                                        q.eq("status", "PENDING")
                                                                                .or(
                                                                                        q2 ->
                                                                                                q2.eq(
                                                                                                                "status",
                                                                                                                "RUNNING")
                                                                                                        .lt(
                                                                                                                "lease_until",
                                                                                                                now())))
                                                        .le("next_run_at", now())
                                                        .orderByAsc("id")
                                                        .last("LIMIT 1 FOR UPDATE SKIP LOCKED"));
                                if (r == null) return null;
                                try {
                                    var fixture =
                                            fixtures.require(
                                                    SimulationFixtureCode.valueOf(
                                                            r.getFixtureCode()),
                                                    r.getFixtureVersion());
                                    var input =
                                            json.read(r.getInputSnapshot(), SimulationInput.class);
                                    require(input.layouts().size() <= 10, "print.simulationLimit");
                                    var journal = new ArrayList<Map<String, Object>>();
                                    var actual = new ArrayList<String>();
                                    boolean passed = true;
                                    for (var layout : input.layouts()) {
                                        var state =
                                                new PrintStateSnapshot(
                                                        PrintJobStatus.PENDING, false, false, 0, 5);
                                        var seen = new HashSet<String>();
                                        for (int stepIndex = 0;
                                                stepIndex < fixture.steps().size();
                                                stepIndex++) {
                                            var step =
                                                    stepIndex == 0
                                                            ? simulated.submit(
                                                                    new SimulationDispatch(
                                                                            layout.contentHash(),
                                                                            fixture.code(),
                                                                            0))
                                                            : simulated.query(
                                                                    new SimulationDispatch(
                                                                            layout.contentHash(),
                                                                            fixture.code(),
                                                                            stepIndex));
                                            if (journal.size() >= 500)
                                                throw new PrintFailure("print.simulationLimit");
                                            if (seen.add(step.eventNo())) {
                                                var transition =
                                                        policy.decide(state, step.observation());
                                                state =
                                                        new PrintStateSnapshot(
                                                                transition.status(),
                                                                false,
                                                                step.observation().partialOutput(),
                                                                0,
                                                                5);
                                            }
                                            journal.add(
                                                    Map.of(
                                                            "at",
                                                            step.virtualMillis(),
                                                            "eventNo",
                                                            step.eventNo(),
                                                            "status",
                                                            state.status().name(),
                                                            "layoutHash",
                                                            layout.contentHash()));
                                        }
                                        actual.add(state.status().name());
                                        passed &= state.status() == fixture.expectedStatus();
                                    }
                                    r.setStatus(passed ? "PASSED" : "FAILED");
                                    r.setExpectedResults(
                                            json.write(
                                                    Map.of(
                                                            "status",
                                                            fixture.expectedStatus().name())));
                                    r.setActualResults(json.write(Map.of("statuses", actual)));
                                    r.setEventJournal(json.write(journal));
                                    r.setFinishedAt(now());
                                    r.setRevision(r.getRevision() + 1);
                                    r.setUpdatedAt(now());
                                    simulations.updateById(r);
                                    return passed;
                                } catch (RuntimeException failure) {
                                    r.setStatus("FAILED");
                                    r.setActualResults(
                                            json.write(
                                                    Map.of(
                                                            "error",
                                                            "SIMULATION_EXECUTION_FAILED")));
                                    r.setFinishedAt(now());
                                    r.setRevision(r.getRevision() + 1);
                                    r.setUpdatedAt(now());
                                    simulations.updateById(r);
                                    return false;
                                }
                            });
            if (result == null) break;
            if (result) done++;
            else failed++;
        }
        return new PrintWorkReport(done + failed, done, failed, done + failed == budget.maxItems());
    }
}
