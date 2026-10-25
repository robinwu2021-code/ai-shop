package ai.neargo.shop.printing.center.service;

import ai.neargo.shop.print.api.*;

public interface PrintGenerationRecovery {
    PrintSubmission recoverGeneration(String requestNo, Long expectedRevision, PrintActor actor);
}
