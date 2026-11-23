package cn.adc.scheduling;

import cn.adc.shared.audit.AuditDocuments;
import cn.adc.shared.audit.CommandRecovery;
import cn.adc.shared.resource.ResourceLockCoordinator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import static cn.adc.scheduling.ResolutionChain.Revision;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.*;

class ResolutionHistoryTest {
    private final ResolutionChain chains = new ResolutionChain();

    @Test void historyProjectionUsesPredecessorOrderAndRejectsPartialHistory() {
        var original = originalDocument();
        var next = correction(original, "10");
        var first = original.get("after");
        var last = next.get("after");
        var service = new ResolutionHistoryReadService(null, null);
        var history = service.completeHistory(List.of(last, first), List.of(next, original)).getFirst();
        assertEquals(20, history.root());
        assertEquals(10, history.head());
        assertEquals(List.of(first, last), history.revisions());
        assertEquals(AuditDocuments.hash(last), history.headFingerprint());
        assertThrows(IllegalArgumentException.class,
                () -> service.completeHistory(List.of(last), List.of(next)));
    }

    @Test void successAuditSqlFailureRollsBackTheConnectionUsedForAppend() throws Exception {
        var source = mock(javax.sql.DataSource.class);
        var connection = mock(Connection.class);
        var setup = mock(java.sql.Statement.class);
        var select = mock(PreparedStatement.class);
        var insert = mock(PreparedStatement.class);
        var audit = mock(PreparedStatement.class);
        when(source.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(setup);
        when(connection.prepareStatement(startsWith("SELECT"))).thenReturn(select);
        when(connection.prepareStatement(startsWith("INSERT INTO adc.appointment_resolution"))).thenReturn(insert);
        when(connection.prepareStatement(startsWith("INSERT INTO adc.operation_audit_event"))).thenReturn(audit);
        when(select.executeQuery()).thenReturn(mock(ResultSet.class), mock(ResultSet.class));
        when(insert.executeUpdate()).thenReturn(1);
        when(audit.executeUpdate()).thenThrow(new java.sql.SQLException("synthetic audit constraint failure", "23514"));
        var transaction = new cn.adc.shared.db.JdbcTransactions(source);
        var service = new ResolutionHistoryService();
        var failure = assertThrows(cn.adc.shared.db.JdbcTransactions.Failure.class, () -> transaction.execute(c -> {
            var change = service.appendLocked(c, originalDocument(), null);
            assertEquals("20", change.businessReference().path("resolutionId").asText());
            try (var statement = c.prepareStatement("INSERT INTO adc.operation_audit_event /* synthetic failure */")) {
                return statement.executeUpdate();
            }
        }));
        assertFalse(failure.unknown());
        assertFalse(failure.commitAttempted());
        assertEquals("23514", failure.sqlState());
        var order = inOrder(insert, audit, connection);
        order.verify(insert).executeUpdate();
        order.verify(audit).executeUpdate();
        order.verify(connection).rollback();
        verify(connection, never()).commit();
    }

    @Test void competingCorrectionWithOldHeadIsRejectedBeforeInsert() throws Exception {
        var original = originalDocument();
        var first = correction(original, "21");
        var competing = correction(original, "22");
        var connection = mock(Connection.class);
        var rowsQuery = mock(PreparedStatement.class);
        var auditQuery = mock(PreparedStatement.class);
        var rows = mock(ResultSet.class);
        var audits = mock(ResultSet.class);
        when(connection.prepareStatement(startsWith("SELECT resolution_id"))).thenReturn(rowsQuery);
        when(connection.prepareStatement(startsWith("SELECT evidence"))).thenReturn(auditQuery);
        when(rowsQuery.executeQuery()).thenReturn(rows);
        when(auditQuery.executeQuery()).thenReturn(audits);
        when(rows.next()).thenReturn(true, true, false);
        when(rows.getString(1)).thenReturn("20", "21");
        when(rows.getString(2)).thenReturn("10");
        when(rows.getInt(3)).thenReturn(2);
        when(rows.getString(4)).thenReturn("50");
        when(rows.getString(5)).thenReturn("synthetic");
        when(rows.getString(6)).thenReturn("pending");
        when(rows.getString(8)).thenReturn("TEST");
        when(rows.getObject(10, java.time.OffsetDateTime.class))
                .thenReturn(java.time.OffsetDateTime.parse("2026-11-14T00:00:00Z"));
        when(audits.next()).thenReturn(true, true, false);
        when(audits.getString(1)).thenReturn(original.toString(), first.toString());
        when(audits.getString(2)).thenReturn("TEST");
        when(audits.getString(3)).thenReturn("RESOLUTION_CREATE", "RESOLUTION_APPEND");
        when(audits.getString(4)).thenReturn(original.get("targetDraftRef").toString());
        var stale = assertThrows(CommandRecovery.Rejection.class,
                () -> new ResolutionHistoryService().appendLocked(connection, competing,
                        AuditDocuments.hash(original.get("after"))));
        assertEquals("RESOLUTION_HEAD_STALE", stale.getMessage());
        verify(connection, never()).prepareStatement(startsWith("INSERT"));
    }

    private static tools.jackson.databind.node.ObjectNode correction(
            tools.jackson.databind.node.ObjectNode original, String id) {
        var next = original.deepCopy().put("operationKind", "RESOLUTION_APPEND")
                .put("predecessorResolutionId", "20").put("newResolutionId", id)
                .put("revisionReason", "synthetic correction");
        next.set("before", original.get("after"));
        ((tools.jackson.databind.node.ObjectNode) next.get("after")).put("resolution_id", id);
        return next;
    }

    @Test void terminalDocumentMustMatchPersistedRequestAndBothSnapshots() {
        var document = originalDocument();
        var reference = document.get("targetDraftRef").deepCopy();
        ((tools.jackson.databind.node.ObjectNode) reference).put("resolutionId", "20");
        var envelope = AuditDocuments.JSON.createObjectNode().put("eventPhase", "BUSINESS_RESULT")
                .put("confirmedCommit", "YES").put("canonicalPayloadHash", "a".repeat(64))
                .put("operationKind", "RESOLUTION_CREATE");
        var snapshot = envelope.putObject("businessResultSnapshot");
        snapshot.set("verification", document);
        snapshot.set("after", document.get("after"));
        snapshot.putNull("before");
        snapshot.set("businessReference", reference);
        assertEquals(document, ResolutionRepository.terminalDocument(envelope, "TEST", "RESOLUTION_CREATE", reference));
        assertThrows(IllegalArgumentException.class,
                () -> ResolutionRepository.terminalDocument(envelope, "OTHER", "RESOLUTION_CREATE", reference));
        snapshot.putNull("after");
        assertThrows(IllegalArgumentException.class,
                () -> ResolutionRepository.terminalDocument(envelope, "TEST", "RESOLUTION_CREATE", reference));
    }

    @Test void changeReferenceMustMatchFormalEvidenceBeforeAnyDatabaseRead() {
        var document = originalDocument();
        var verification = (tools.jackson.databind.node.ObjectNode) document.get("verification");
        var after = (tools.jackson.databind.node.ObjectNode) document.get("after");
        after.put("appointment_change_id", "42");
        var connection = mock(Connection.class);
        var service = new ResolutionHistoryService();
        assertThrows(CommandRecovery.Rejection.class,
                () -> service.verifyFormalEvidence(connection, 7, 50, document));
        verification.putObject("formalEvidence").put("ucCode", "UC03")
                .put("auditSuccessEventId", "100").put("appointmentChangeId", "43");
        assertThrows(CommandRecovery.Rejection.class,
                () -> service.verifyFormalEvidence(connection, 7, 50, document));
        verifyNoInteractions(connection);
    }

    @Test void recoveryRecognizesOriginalSuccessAfterAppendAndRequiresBothCommittedFacts() {
        var original = originalDocument();
        var successor = correction(original, "21");
        var revised = (tools.jackson.databind.node.ObjectNode) successor.get("after");
        var service = new ResolutionHistoryService();
        assertTrue(service.historyContains(original, List.of(original.get("after"), revised), List.of(original, successor)));
        assertFalse(service.historyContains(original, List.of(original.get("after")), List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> service.historyContains(original, List.of(revised), List.of(original, successor)));
    }

    private static tools.jackson.databind.node.ObjectNode originalDocument() {
        var snapshot = AuditDocuments.parse("""
                {"resolution_id":"20","schedule_id":"10","schedule_version_no":2,"appointment_id":"50",
                 "resolution_subject":"synthetic","resolution_result":"pending","resolution_basis":null,
                 "responsible_party":"TEST","pending_confirmation":null,
                 "recorded_at":"2026-11-14T00:00:00Z","appointment_change_id":null}
                """);
        var document = AuditDocuments.JSON.createObjectNode().put("schemaVersion", 1)
                .put("operationKind", "RESOLUTION_CREATE").put("requestReference", "TEST")
                .put("canonicalPayloadHash", "a".repeat(64)).put("rootResolutionId", "20")
                .putNull("predecessorResolutionId").put("newResolutionId", "20")
                .putNull("revisionReason").putNull("before");
        document.putObject("targetDraftRef").put("scheduleId", "10").put("versionNo", 2);
        document.set("after", snapshot);
        document.putObject("verification").put("evaluatedAt", "2026-11-14T00:00:00Z")
                .put("appointmentFingerprint", "b".repeat(64)).put("conclusion", "PENDING")
                .putNull("formalEvidence").putNull("pendingReason").putArray("supportReasons");
        return document;
    }

    @Test void completeHistoryRequiresOneMatchingTerminalDocumentPerPersistedRow() {
        var document = originalDocument();
        var snapshot = document.get("after");
        assertEquals(20, chains.verifiedRevisions(List.of(snapshot), List.of(document)).getFirst().id());
        assertThrows(IllegalArgumentException.class, () -> chains.verifiedRevisions(List.of(snapshot), List.of()));
        assertThrows(IllegalArgumentException.class, () -> chains.verifiedRevisions(List.of(snapshot), List.of(document, document)));
        var altered = (tools.jackson.databind.node.ObjectNode) snapshot.deepCopy();
        altered.put("resolution_result", "changed");
        assertThrows(IllegalArgumentException.class, () -> chains.verifiedRevisions(List.of(altered), List.of(document)));
    }

    @Test void originalAppendSuppliesFullTerminalDocumentAndRejectsUnexpectedHeadToken() throws Exception {
        var connection = mock(Connection.class);
        var select = mock(PreparedStatement.class);
        var insert = mock(PreparedStatement.class);
        when(connection.prepareStatement(startsWith("SELECT"))).thenReturn(select);
        when(connection.prepareStatement(startsWith("INSERT"))).thenReturn(insert);
        when(select.executeQuery()).thenReturn(mock(ResultSet.class));
        when(insert.executeUpdate()).thenReturn(1);
        var service = new ResolutionHistoryService();
        var document = originalDocument();

        assertThrows(IllegalArgumentException.class,
                () -> service.appendLocked(connection, document, "old-token"));
        verifyNoInteractions(insert);
        var change = service.appendLocked(connection, document, null);
        assertEquals(document, change.verification());
        assertEquals(document.get("after"), change.after());
        assertTrue(change.before().isNull());
        verify(insert).executeUpdate();
    }

    @Test void commandLocksInOrderAndRereadsDraftBeforeInvokingDocumentBuilder() throws Exception {
        var commands = mock(CommandRecovery.class, withSettings().mockMaker(org.mockito.MockMakers.INLINE));
        var ticket = mock(CommandRecovery.Ticket.class, withSettings().mockMaker(org.mockito.MockMakers.INLINE));
        var connection = mock(Connection.class);
        var query = mock(PreparedStatement.class);
        when(connection.prepareStatement(startsWith("SELECT"))).thenReturn(query);
        when(query.executeQuery()).thenReturn(mock(ResultSet.class));
        var locks = mock(ResourceLockCoordinator.Scope.class, withSettings().mockMaker(org.mockito.MockMakers.INLINE));
        var draft = new ScheduleStateToken.Version(10, 2, 7, 9, null, null, "DRAFT", null, null, null, null, null);
        var command = new CommandRecovery.Command("RESOLUTION_APPEND", "UC22", UUID.randomUUID(),
                AuditDocuments.JSON.createObjectNode(), Set.of(7L));
        when(commands.begin(command)).thenReturn(ticket);
        when(commands.perform(eq(ticket), any())).thenAnswer(call -> {
            CommandRecovery.Work work = call.getArgument(1);
            return work.run(connection, locks);
        });
        var builder = mock(ResolutionHistoryService.DocumentFactory.class);
        var service = new ResolutionHistoryService();
        var stale = assertThrows(CommandRecovery.Rejection.class,
                () -> service.execute(commands, command, draft, 50, 20L, "token", builder));
        assertEquals("RESOLUTION_DRAFT_STALE", stale.getMessage());
        var order = inOrder(locks, query);
        order.verify(locks).schedules(List.of(10L));
        order.verify(locks).dentists(List.of(9L));
        order.verify(locks).versions(List.of(new ResourceLockCoordinator.Version(10, 2)));
        order.verify(locks).appointments(List.of(50L));
        order.verify(locks).roots(List.of(20L));
        order.verify(query).executeQuery();
        verifyNoInteractions(builder);
    }

    @Test void lockedCommandAppendsBeforeHandingTheCompleteSnapshotToTheJournal() throws Exception {
        var commands = mock(CommandRecovery.class, withSettings().mockMaker(org.mockito.MockMakers.INLINE));
        var ticket = mock(CommandRecovery.Ticket.class, withSettings().mockMaker(org.mockito.MockMakers.INLINE));
        var locks = mock(ResourceLockCoordinator.Scope.class, withSettings().mockMaker(org.mockito.MockMakers.INLINE));
        var connection = mock(Connection.class);
        var selectDraft = mock(PreparedStatement.class);
        var selectHistory = mock(PreparedStatement.class);
        var insert = mock(PreparedStatement.class);
        when(connection.prepareStatement(startsWith("SELECT schedule_id,schedule_version_no,clinic_id")))
                .thenReturn(selectDraft);
        when(connection.prepareStatement(startsWith("SELECT resolution_id"))).thenReturn(selectHistory);
        when(connection.prepareStatement(startsWith("SELECT evidence"))).thenReturn(selectHistory);
        when(connection.prepareStatement(startsWith("INSERT"))).thenReturn(insert);
        when(selectHistory.executeQuery()).thenReturn(mock(ResultSet.class), mock(ResultSet.class));
        var version = mock(ResultSet.class);
        when(selectDraft.executeQuery()).thenReturn(version);
        when(version.next()).thenReturn(true, false);
        when(version.getLong(1)).thenReturn(10L);
        when(version.getInt(2)).thenReturn(2);
        when(version.getLong(3)).thenReturn(7L);
        when(version.getLong(4)).thenReturn(9L);
        when(version.getString(7)).thenReturn("DRAFT");
        when(insert.executeUpdate()).thenReturn(1);
        var draft = new ScheduleStateToken.Version(10, 2, 7, 9, null, null, "DRAFT", null, null, null, null, null);
        var command = new CommandRecovery.Command("RESOLUTION_CREATE", "UC22", UUID.randomUUID(),
                AuditDocuments.JSON.createObjectNode(), Set.of(7L));
        var document = originalDocument();
        when(commands.begin(command)).thenReturn(ticket);
        when(ticket.requestReference()).thenReturn("TEST");
        when(ticket.canonicalPayloadHash()).thenReturn("a".repeat(64));
        when(commands.perform(eq(ticket), any())).thenAnswer(call -> {
            CommandRecovery.Work work = call.getArgument(1);
            var change = work.run(connection, locks);
            verify(insert).executeUpdate();
            assertEquals(document, change.verification());
            assertEquals("20", change.businessReference().path("resolutionId").asText());
            return new CommandRecovery.Receipt("TEST", "SUCCEEDED", CommandRecovery.Persistence.CONFIRMED,
                    change.after(), false);
        });
        var receipt = new ResolutionHistoryService().execute(commands, command, draft, 50, null, null,
                (c, ref, hash) -> document);
        assertEquals(document.get("after"), receipt.result());
        verify(locks, never()).roots(any());
    }

    @Test void unorderedIndependentRootsRetainSeparateHeadsAndRejectStaleToken() {
        var original = row(20, 20, null);
        var change = row(10, 20, original);
        var independent = row(30, 30, null);
        var rows = List.of(change, independent, original);
        assertEquals(10, chains.heads(rows).get(20L).id());
        assertEquals(30, chains.heads(rows).get(30L).id());
        assertTrue(chains.expectedHead(rows, 20, 20, AuditDocuments.hash(original.snapshot())).isEmpty());
        assertEquals(change, chains.expectedHead(rows, 20, 10, AuditDocuments.hash(change.snapshot())).orElseThrow());
    }

    @Test void refusesForkMissingPredecessorAndDisconnectedCycle() {
        var original = row(20, 20, null);
        var change = row(21, 20, original);
        assertThrows(IllegalArgumentException.class, () -> chains.heads(List.of(original, change, row(22, 20, original))));
        assertThrows(IllegalArgumentException.class, () -> chains.heads(List.of(original, row(22, 20, change))));
        var first = new Revision(21, 20, 22L, original.snapshot(), AuditDocuments.hash(original.snapshot()));
        var second = new Revision(22, 20, 21L, original.snapshot(), AuditDocuments.hash(original.snapshot()));
        assertThrows(IllegalArgumentException.class, () -> chains.heads(List.of(original, first, second)));
    }

    @Test void refusesChangedTargetAndFingerprintMismatch() {
        var original = row(20, 20, null);
        var change = row(21, 20, original);
        var wrong = new Revision(21, 20, 20L, change.snapshot(), "tampered");
        assertThrows(IllegalArgumentException.class, () -> chains.heads(List.of(original, wrong)));
        var replacement = AuditDocuments.JSON.createObjectNode().put("appointment_id", "other");
        var moved = new Revision(21, 20, 20L, replacement, change.previousFingerprint());
        assertThrows(IllegalArgumentException.class, () -> chains.heads(List.of(original, moved)));
    }

    private static Revision row(long id, long root, Revision prior) {
        var snapshot = AuditDocuments.JSON.createObjectNode().put("resolution_id", Long.toString(id))
                .put("schedule_id", "10").put("schedule_version_no", 2).put("appointment_id", "50");
        return new Revision(id, root, prior == null ? null : prior.id(), snapshot,
                prior == null ? null : AuditDocuments.hash(prior.snapshot()));
    }
}
