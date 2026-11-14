package ai.neargo.shop.beauty.service.impl;

import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.neargo.shop.beauty.entity.BtyServiceSubmission;

import org.junit.jupiter.api.Test;

/** Parser failures prove only the local immutable-original boundary, never a real owner effect. */
class TaskStartOriginalReaderTest {
    @Test
    void absentAndWrongFamilyCannotBePresentedAsAStartOriginal() {
        assertThrows(RuntimeException.class, () -> TaskStartJournal.readFrozen(null));
        var row = new BtyServiceSubmission();
        row.setAction("START");
        row.setFrozenCommand("{\"schema\":\"START_MANUAL_V1\"}");
        assertThrows(RuntimeException.class, () -> TaskStartJournal.readFrozen(row));
    }

    @Test
    void v2CannotCoerceOrInventMissingSelectedSlots() {
        var row = new BtyServiceSubmission();
        row.setAction("TASK_ACTION");
        row.setFrozenCommand(
                "{\"schema\":\"TASK_ACTION_V2\",\"ownSlots\":null,\"acceptedRoster\":[]}");
        assertThrows(RuntimeException.class, () -> TaskActionV2Original.read(row));
        row.setFrozenCommand(
                "{\"schema\":\"TASK_ACTION_V2\",\"ownSlots\":[],\"acceptedRoster\":[],\"actorOverride\":\"X\"}");
        assertThrows(RuntimeException.class, () -> TaskActionV2Original.read(row));
    }
}
