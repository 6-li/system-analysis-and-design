package cn.adc.scheduling;

import cn.adc.shared.audit.AuditDocuments;
import cn.adc.shared.audit.CommandRecovery;
import cn.adc.shared.config.*;
import cn.adc.shared.db.JdbcTransactions;
import cn.adc.shared.resource.ResourceLockCoordinator;
import cn.adc.shared.security.StaffAuthorization;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;
import tools.jackson.databind.node.ObjectNode;

import javax.sql.DataSource;
import java.sql.*;
import java.time.*;
import java.util.*;

import static cn.adc.shared.audit.AuditDocuments.JSON;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Application behavior on mocked persistence and JDBC; never connects to PostgreSQL. */
class ScheduleServiceOfflineTest {
    private static final Instant NOW=Instant.parse("2026-11-07T01:00:00Z");
    private static final Clock CLOCK=Clock.fixed(NOW,ZoneOffset.UTC);
    private static final SourceStamp SOURCE=new SourceStamp("synthetic","v1","fixture",NOW,true);
    private final Connection conn=mock(Connection.class);
    private final ScheduleStore store=mock(ScheduleStore.class);
    private final DentistEligibilityProvider dentistSource=mock(DentistEligibilityProvider.class);
    private final ScheduleDraftService.Coordination coordination=mock(ScheduleDraftService.Coordination.class);
    private ScheduleDraftService service;
    private CommandRecovery.SuccessVerifier verifier;
    private CommandRecovery.Command submitted;
    private AuditDocuments.Change change;
    private boolean auditFails;
    private boolean access=true;
    private static <T>T mock(Class<T> type){
        return org.mockito.Mockito.mock(type,withSettings().mockMaker(MockMakers.PROXY));
    }

    @BeforeEach void setup() throws Exception {
        var ds=mock(DataSource.class);
        when(ds.getConnection()).thenReturn(conn);
        when(conn.createStatement()).thenReturn(mock(Statement.class));
        when(conn.prepareStatement(anyString())).thenAnswer(x->mock(PreparedStatement.class));
        var tx=new JdbcTransactions(ds);
        var auth=new StaffAuthorization(()->Optional.of("manager"),()->access
                ? Map.of("manager",new StaffAuthorization.Grant("v1",Set.of("CLINIC_MANAGER"),Set.of("UC21"),Set.of(1L,2L),false))
                : Map.of());
        when(dentistSource.require(7L,1L)).thenReturn(new DentistEligibilityProvider.Eligibility(7,1,"verified",SOURCE));
        when(dentistSource.require(7L,2L)).thenReturn(new DentistEligibilityProvider.Eligibility(7,2,"verified",SOURCE));
        when(store.nextId(conn,false)).thenReturn(80L);
        when(store.nextId(conn,true)).thenReturn(90L);
        service=new ScheduleDraftService(tx,auth,id->new ClinicDirectoryProvider.Clinic(id,"fixture",
                ZoneId.of(id==1?"Asia/Shanghai":"America/New_York"),SOURCE),dentistSource,coordination,CLOCK,store,
                v->{verifier=v;return (command,work)->tx.execute(c->{
                    submitted=command;
                    var locks=new ResourceLockCoordinator().scope(c);
                    locks.command("mock-scope");
                    var result=work.run(c,locks);
                    if(auditFails)throw new SQLException("synthetic audit insert failure","23514");
                    change=result;
                    return new CommandRecovery.Receipt("mock","SUCCEEDED",CommandRecovery.Persistence.CONFIRMED,
                            result.after(),false,"5",null);
                });});
    }

    @Test void createsOnlyDraftAfterGlobalDentistLock() throws Exception {
        var first=service.create(UUID.randomUUID(),draft());
        assertEquals(1,first.result().path("scheduleRef").path("versionNo").asInt());
        assertTrue(first.result().path("appointmentImpact").path("appointmentIds").isEmpty());
        var order=inOrder(conn,dentistSource,store);
        order.verify(conn).prepareStatement("SELECT adc_uc21_lock.lock_dentist(?)");
        order.verify(dentistSource).require(7,1);
        order.verify(store).overlaps(eq(conn),eq(7L),any());
        order.verify(store).nextId(conn,false);
        order.verify(store).insertDraft(eq(conn),argThat(v->v.status().equals("DRAFT")&&v.activatedAt()==null));
        verify(conn,never()).prepareStatement("SELECT pg_advisory_xact_lock(?)");
        verify(conn,never()).prepareStatement("SELECT adc_uc21_lock.lock_version(?,?)");
    }

    @Test void crossClinicEffectiveRequiresVerifiedCoordinationAndNeverAllocatesOnFailure() throws Exception {
        when(store.overlaps(eq(conn),eq(7L),any())).thenReturn(List.of(version(81,1,2,"EFFECTIVE")));
        when(coordination.confirm(eq(1L),eq(7L),any(),eq("claim"),anyList())).thenReturn(Optional.empty());
        assertEquals("COORDINATION_REQUIRED",assertThrows(CommandRecovery.Rejection.class,
                ()->service.create(UUID.randomUUID(),draft().put("coordinationBasis","claim"))).getMessage());
        verify(store,never()).nextId(any(),anyBoolean());
        verify(conn).rollback();
        when(coordination.confirm(eq(1L),eq(7L),any(),eq("claim"),anyList())).thenReturn(Optional.of("verified-claim"));
        service.create(UUID.randomUUID(),draft().put("coordinationBasis","claim"));
        verify(store).insertDraft(eq(conn),argThat(v->"verified-claim".equals(v.coordination())));
    }

    @Test void allowedDraftOverlapDoesNotBecomeEffective() throws Exception {
        when(store.overlaps(eq(conn),eq(7L),any())).thenReturn(List.of(version(81,1,2,"DRAFT")));
        service.create(UUID.randomUUID(),draft());
        verify(store).insertDraft(eq(conn),argThat(v->v.status().equals("DRAFT")&&v.conflict().equals("OVERLAP")));
    }

    @Test void independentLocalEffectiveConflictCannotBeSavedAsAnOverlapOfDrafts() throws Exception {
        when(store.overlaps(eq(conn),eq(7L),any())).thenReturn(List.of(version(81,1,1,"EFFECTIVE")));
        assertEquals("EFFECTIVE_SCHEDULE_CONFLICT",assertThrows(CommandRecovery.Rejection.class,
                ()->service.create(UUID.randomUUID(),draft())).getMessage());
        verify(store,never()).nextId(any(),anyBoolean());
    }

    @Test void ambiguousLocalTimeNeedsAValidExplicitOffsetAndGapIsRejected() throws Exception {
        var gap=draft().put("clinicId","2").put("startsAt","2026-03-08T02:30:00-05:00")
                .put("endsAt","2026-03-08T04:00:00-04:00");
        assertEquals("OFFSET_NOT_VALID_FOR_CLINIC",assertThrows(CommandRecovery.Rejection.class,
                ()->service.create(UUID.randomUUID(),gap)).getMessage());
        var fold=gap.put("startsAt","2026-11-01T01:30:00-04:00").put("endsAt","2026-11-01T01:30:00-05:00");
        service.create(UUID.randomUUID(),fold);
        verify(store).insertDraft(eq(conn),argThat(v->Duration.between(v.startsAt(),v.endsAt()).equals(Duration.ofHours(1))));
    }

    @Test void revisionRereadsAfterLocksAndRejectsASecondSubmissionOfOldToken() throws Exception {
        var base=version(80,1,1,"EFFECTIVE");
        var later=version(80,2,1,"DRAFT");
        when(store.versions(conn,80)).thenReturn(List.of(base),List.of(base),List.of(base,later),List.of(base,later));
        var data=revision(base);
        assertEquals(2,service.revise(UUID.randomUUID(),80,data).result().path("scheduleRef").path("versionNo").asInt());
        var order=inOrder(conn,store);
        order.verify(conn).prepareStatement("SELECT pg_advisory_xact_lock(?)");
        order.verify(conn).prepareStatement("SELECT adc_uc21_lock.lock_dentist(?)");
        order.verify(conn).prepareStatement("SELECT adc_uc21_lock.lock_version(?,?)");
        order.verify(store).versions(conn,80);
        assertEquals("STALE_VERSION",assertThrows(CommandRecovery.Rejection.class,
                ()->service.revise(UUID.randomUUID(),80,data)).getMessage());
        verify(store,times(1)).insertDraft(eq(conn),any());
        data.putNull("previousIssueId");
        assertThrows(IllegalArgumentException.class,()->service.revise(UUID.randomUUID(),80,data));
    }

    @Test void firstIssuePreservesInvalidInputWithoutAllocatingScheduleIdentity() throws Exception {
        when(dentistSource.require(9,1)).thenThrow(new ConfigurationUnavailable());
        var request=JSON.createObjectNode().put("failureReason","无法核验");
        request.putObject("submittedContext").put("clinicId","1").put("dentistId","9").put("submittedStartsAt","原始输入");
        var receipt=service.recordIssue(UUID.randomUUID(),request);
        assertEquals("90",receipt.result().path("issueId").asText());
        verify(store).insertIssue(eq(conn),argThat(i->i.dentist()==null&&i.basis()==null&&i.requestedStart()==null
                &&i.context().contains("原始输入")));
        verify(store,never()).nextId(conn,false);
        verify(store,never()).insertDraft(any(),any());
    }

    @Test void retainedHistoryRemainsReadableButIsNotAutomaticallyARevisionBasis() throws Exception {
        var superseded=version(80,1,1,"SUPERSEDED");
        var current=version(80,2,1,"EFFECTIVE");
        var oldDraft=version(80,3,1,"DRAFT");
        var latestDraft=version(80,4,1,"DRAFT");
        var unverified=version(80,5,1,"LEGACY");
        var rows=List.of(superseded,current,oldDraft,latestDraft,unverified);
        when(store.versions(conn,80)).thenReturn(rows);
        assertFalse(service.version(80,5).has("activationStatus"));
        for(var basis:List.of(superseded,oldDraft,unverified)){
            var token=service.version(80,basis.versionNo()).path("stateToken").asText();
            assertEquals("HISTORICAL_BASIS_NOT_ELIGIBLE",assertThrows(CommandRecovery.Rejection.class,
                    ()->service.revise(UUID.randomUUID(),80,revision(basis).put("expectedStateToken",token))).getMessage());
        }
        verify(store,never()).insertDraft(any(),any());
        verify(store,never()).linkIssue(any(),any(),any());
    }

    @Test void standaloneRevisionRetainsImpactEvidenceWithoutGuessingAnOldIssue() throws Exception {
        var base=version(80,1,1,"EFFECTIVE");
        when(store.versions(conn,80)).thenReturn(List.of(base));
        when(store.affectedAppointments(eq(conn),eq(1L),eq(1L),eq(80L),eq(7L),any(),eq(false))).thenReturn(List.of("301","302"));
        service.revise(UUID.randomUUID(),80,revision(base));
        assertEquals(List.of("301","302"),change.after().path("appointmentImpact").path("appointmentIds")
                .valueStream().map(n->n.asText()).toList());
        assertFalse(change.after().has("linkedIssueId"));
        verify(store,never()).linkIssue(any(),any(),any());
    }

    @Test void revisionWithAnExactOpenIssueLinksUnderLockAndRecoveryKeepsOriginalUntruncatedImpact() throws Exception {
        var base=version(80,1,1,"EFFECTIVE");
        var draft=version(80,2,1,"DRAFT");
        var basis=new ScheduleStore.Ref(80,1);
        var result=new ScheduleStore.Ref(80,2);
        var open=oldIssue(1,basis,null);
        when(store.versions(conn,80)).thenReturn(List.of(base));
        when(store.issue(conn,91)).thenReturn(open);
        when(store.linkIssue(conn,open,result)).thenReturn(true);
        var ids=java.util.stream.IntStream.rangeClosed(300,405).mapToObj(Integer::toString).toList();
        when(store.affectedAppointments(eq(conn),eq(1L),eq(1L),eq(80L),eq(7L),any(),eq(false))).thenReturn(ids);
        var saved=service.revise(UUID.randomUUID(),80,revision(base).put("previousIssueId","091"));
        assertEquals("91",submitted.payload().path("previousIssueId").asText());
        assertEquals(ids,saved.result().path("appointmentImpact").path("appointmentIds").valueStream().map(n->n.asText()).toList());
        var order=inOrder(conn,store);
        order.verify(conn).prepareStatement("SELECT adc_uc21_lock.lock_version(?,?)");
        order.verify(store).issue(conn,91);
        order.verify(store).insertDraft(eq(conn),argThat(v->v.versionNo()==2));
        order.verify(store).linkIssue(conn,open,result);
        when(store.versions(conn,80)).thenReturn(List.of(base,draft));
        when(store.issue(conn,91)).thenReturn(oldIssue(1,basis,result));
        when(store.affectedAppointments(eq(conn),eq(1L),eq(1L),eq(80L),eq(7L),any(),eq(false))).thenReturn(List.of("999"));
        var snapshot=JSON.createObjectNode();
        snapshot.set("after",change.after());snapshot.set("before",change.before());
        snapshot.set("businessReference",change.businessReference());snapshot.set("verification",change.verification());
        assertTrue(verifier.matchesCommitted(conn,submitted,snapshot));
        verify(store,times(1)).affectedAppointments(eq(conn),eq(1L),eq(1L),eq(80L),eq(7L),any(),eq(false));
        snapshot.path("verification").asObject().put("impactClinicId","2");
        assertFalse(verifier.matchesCommitted(conn,submitted,snapshot));
    }

    @Test void revisionRejectsMissingOutOfScopeMismatchedOrAlreadyLinkedIssues() throws Exception {
        var base=version(80,1,1,"EFFECTIVE");
        var request=revision(base).put("previousIssueId","91");
        when(store.versions(conn,80)).thenReturn(List.of(base));
        assertEquals("NOT_FOUND",assertThrows(CommandRecovery.Rejection.class,()->service.revise(UUID.randomUUID(),80,request)).getMessage());
        when(store.issue(conn,91)).thenReturn(oldIssue(3,new ScheduleStore.Ref(80,1),null));
        assertThrows(SecurityException.class,()->service.revise(UUID.randomUUID(),80,request));
        when(store.issue(conn,91)).thenReturn(oldIssue(1,new ScheduleStore.Ref(80,2),null));
        assertEquals("ISSUE_BASIS_MISMATCH",assertThrows(CommandRecovery.Rejection.class,()->service.revise(UUID.randomUUID(),80,request)).getMessage());
        when(store.issue(conn,91)).thenReturn(oldIssue(1,new ScheduleStore.Ref(80,1),new ScheduleStore.Ref(80,2)));
        assertEquals("ISSUE_ALREADY_LINKED",assertThrows(CommandRecovery.Rejection.class,()->service.revise(UUID.randomUUID(),80,request)).getMessage());
        verify(store,never()).insertDraft(any(),any());
    }

    @Test void revisionLostIssueCompareAndSetRollsBackBeforeReturningImpact() throws Exception {
        var base=version(80,1,1,"EFFECTIVE");
        when(store.versions(conn,80)).thenReturn(List.of(base));
        when(store.issue(conn,91)).thenReturn(oldIssue(1,new ScheduleStore.Ref(80,1),null));
        assertEquals("ISSUE_ALREADY_LINKED",assertThrows(CommandRecovery.Rejection.class,
                ()->service.revise(UUID.randomUUID(),80,revision(base).put("previousIssueId","91"))).getMessage());
        verify(conn).rollback();
        verify(store,never()).affectedAppointments(any(),anyLong(),anyLong(),anyLong(),anyLong(),any(),anyBoolean());
        assertNull(change);
    }

    @Test void committedImpactIsNotDisclosedAfterAuthorizationIsRevoked() throws Exception {
        doAnswer(i->{access=false;return null;}).when(conn).commit();
        assertThrows(SecurityException.class,()->service.create(UUID.randomUUID(),draft()));
        assertNotNull(change);
        verify(conn).commit();
        verify(conn,never()).rollback();
    }

    private static ScheduleStore.Issue oldIssue(long clinic,ScheduleStore.Ref basis,ScheduleStore.Ref result){
        return new ScheduleStore.Issue(91,"old",clinic,7L,basis,result,null,"reason","{}","manager",NOW,null,null);
    }

    @Test void issueChainRetainsOriginalBasisAndCannotEnterFirstCreate() throws Exception {
        var basis=new ScheduleStore.Ref(80,1);
        var earlier=new ScheduleStore.Issue(91,"old",1,7L,basis,null,null,"reason","{}","manager",NOW,null,null);
        when(store.issue(conn,91)).thenReturn(earlier);
        when(store.versions(conn,80)).thenReturn(List.of(version(80,1,1,"EFFECTIVE")));
        var request=JSON.createObjectNode().put("failureReason","调整失败").put("previousIssueId","91");
        request.putObject("submittedContext").put("clinicId","1");
        service.recordIssue(UUID.randomUUID(),request);
        verify(store).insertIssue(eq(conn),argThat(i->basis.equals(i.basis())&&i.previous()==91));
        assertEquals("EXISTING_BASIS_REQUIRES_REVISION",assertThrows(CommandRecovery.Rejection.class,
                ()->service.create(UUID.randomUUID(),draft().put("previousIssueId","91"))).getMessage());
        verify(store,never()).nextId(conn,false);
    }

    @Test void failedCrossClinicRevisionRecordsTargetAndRealOldBasisWhenBothAreAuthorized() throws Exception {
        when(store.versions(conn,80)).thenReturn(List.of(version(80,1,1,"EFFECTIVE")));
        var request=JSON.createObjectNode().put("failureReason","无法核验");
        request.putObject("submittedContext").put("clinicId","2").putObject("basisRef").put("scheduleId","80").put("versionNo",1);
        service.recordIssue(UUID.randomUUID(),request);
        assertEquals(Set.of(1L,2L),submitted.clinics());
        verify(store).insertIssue(eq(conn),argThat(i->i.clinic()==2&&i.basis().equals(new ScheduleStore.Ref(80,1))));
    }

    @Test void noSuccessOnLostCompareAndSetOrAuditFailure() throws Exception {
        when(store.issue(conn,91)).thenReturn(new ScheduleStore.Issue(91,"old",1,7L,null,null,null,"reason","{}","manager",NOW,null,null));
        assertEquals("ISSUE_ALREADY_LINKED",assertThrows(CommandRecovery.Rejection.class,
                ()->service.create(UUID.randomUUID(),draft().put("previousIssueId","91"))).getMessage());
        auditFails=true;
        var failure=assertThrows(JdbcTransactions.Failure.class,()->service.create(UUID.randomUUID(),draft()));
        assertFalse(failure.unknown());
        verify(conn,times(2)).rollback();
        verify(conn,never()).commit();
        assertNull(change);
    }

    @Test void committedFactSurvivesLaterVersionAndEffectiveStateButRejectsWrongAuditReference() throws Exception {
        service.create(UUID.randomUUID(),draft());
        var original=version(80,1,1,"EFFECTIVE");
        when(store.versions(conn,80)).thenReturn(List.of(original,version(80,2,1,"DRAFT")));
        var envelope=JSON.createObjectNode();
        envelope.set("before",change.before());envelope.set("after",change.after());
        envelope.set("businessReference",change.businessReference());envelope.set("verification",change.verification());
        assertTrue(verifier.matchesCommitted(conn,submitted,envelope));
        ObjectNode different=(ObjectNode)submitted.payload().deepCopy();
        different.put("startsAt","2026-11-07T09:15:00+08:00");
        var differentCommand=new CommandRecovery.Command(submitted.operation(),submitted.ucCode(),submitted.clientKey(),
                different,submitted.clinics());
        assertFalse(verifier.matchesCommitted(conn,differentCommand,envelope));
        envelope.putObject("businessReference").put("scheduleId","80").put("versionNo",2);
        assertFalse(verifier.matchesCommitted(conn,submitted,envelope));
    }

    @Test void issueRecoveryUsesOriginalSubmittedContextAndReason() throws Exception {
        var request=JSON.createObjectNode().put("failureReason","缺少确认");
        request.putObject("submittedContext").put("clinicId","1").put("submittedStartsAt","无法解析的输入");
        service.recordIssue(UUID.randomUUID(),request);
        var saved=org.mockito.ArgumentCaptor.forClass(ScheduleStore.Issue.class);
        verify(store).insertIssue(eq(conn),saved.capture());
        when(store.issue(conn,90)).thenReturn(saved.getValue());
        var envelope=JSON.createObjectNode();
        envelope.set("after",change.after());envelope.set("businessReference",change.businessReference());
        envelope.set("verification",change.verification());
        assertTrue(verifier.matchesCommitted(conn,submitted,envelope));
        var wrong=(ObjectNode)submitted.payload().deepCopy();
        wrong.put("failureReason","另一个原因");
        assertFalse(verifier.matchesCommitted(conn,new CommandRecovery.Command(submitted.operation(),submitted.ucCode(),
                submitted.clientKey(),wrong,submitted.clinics()),envelope));
    }

    @Test void legacySuccessWithoutImpactCannotBeReconstructedFromCurrentAppointments() throws Exception {
        service.create(UUID.randomUUID(),draft());
        when(store.versions(conn,80)).thenReturn(List.of(version(80,1,1,"DRAFT")));
        var legacy=JSON.createObjectNode();
        var oldAfter=change.after().deepCopy().asObject();
        oldAfter.remove("appointmentImpact");
        legacy.set("before",change.before());legacy.set("after",oldAfter);
        legacy.set("businessReference",change.businessReference());legacy.set("verification",change.verification());
        assertFalse(verifier.matchesCommitted(conn,submitted,legacy));
        verify(store,never()).affectedAppointments(any(),anyLong(),anyLong(),anyLong(),anyLong(),any(),anyBoolean());
    }

    @Test void unavailableSourceAndRevokedRoleDoNotCreateDraft() throws Exception {
        when(dentistSource.require(7,1)).thenThrow(new ConfigurationUnavailable());
        assertThrows(JdbcTransactions.Failure.class,()->service.create(UUID.randomUUID(),draft()));
        access=false;
        assertThrows(SecurityException.class,()->service.create(UUID.randomUUID(),draft()));
        verify(store,never()).nextId(any(),anyBoolean());
    }

    private static ObjectNode draft(){
        return JSON.createObjectNode().put("clinicId","1").put("dentistId","7")
                .put("startsAt","2026-11-07T09:00:00+08:00").put("endsAt","2026-11-07T10:00:00+08:00");
    }
    private static ScheduleStateToken.Version version(long id,int no,long clinic,String status){
        return new ScheduleStateToken.Version(id,no,clinic,7,NOW,NOW.plusSeconds(3600),status,null,"verified","CLEAR",null,
                status.equals("EFFECTIVE")?NOW:null);
    }
    private static ObjectNode revision(ScheduleStateToken.Version base){
        var r=draft().put("expectedStateToken",ScheduleStateToken.of(base,List.of(base))).put("changeBasis","理由");
        r.putObject("basisRef").put("scheduleId",Long.toString(base.scheduleId())).put("versionNo",base.versionNo());
        return r;
    }
}
