package cn.adc.scheduling;

import cn.adc.shared.audit.CommandRecovery;
import cn.adc.shared.config.SourceStamp;
import cn.adc.shared.security.StaffSessionIdentityAdapter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;
import org.springframework.http.MediaType;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import static cn.adc.shared.audit.AuditDocuments.JSON;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ScheduleControllerOfflineTest {
    private final ScheduleAccess access=mock(ScheduleAccess.class,withSettings().mockMaker(MockMakers.PROXY));
    private final ScheduleOperations service=mock(ScheduleOperations.class,withSettings().mockMaker(MockMakers.PROXY));
    private MockMvc mvc;

    @BeforeEach void setup(){
        var stamp=new SourceStamp("synthetic","1","tester",Instant.EPOCH,true);
        when(access.identity(any())).thenReturn(new StaffSessionIdentityAdapter(
                ()->new StaffSessionIdentityAdapter.VerifiedSession("manager","csrf",stamp)));
        when(access.service(any())).thenReturn(service);
        mvc=MockMvcBuilders.standaloneSetup(new ScheduleController(access)).build();
    }

    @Test void committedDraftAndIssueUseDifferentLinks() throws Exception{
        var draft=JSON.createObjectNode();
        draft.putObject("scheduleRef").put("scheduleId","80").put("versionNo",1);
        draft.putObject("appointmentImpact").putArray("appointmentIds").add("9007199254740999");
        when(service.create(any(),any())).thenReturn(new CommandRecovery.Receipt("ref","SUCCEEDED",
                CommandRecovery.Persistence.CONFIRMED,draft,false,"5",null));
        write("/schedule-drafts").andExpect(status().isOk())
                .andExpect(jsonPath("$.links.scheduleRef.scheduleId").value("80"))
                .andExpect(jsonPath("$.businessOutcome").value("DRAFT"))
                .andExpect(jsonPath("$.appointmentImpact.appointmentIds[0]").value("9007199254740999"));
        when(service.recordIssue(any(),any())).thenReturn(new CommandRecovery.Receipt("ref","SUCCEEDED",
                CommandRecovery.Persistence.CONFIRMED,JSON.createObjectNode().put("issueId","90"),false,"6",null));
        write("/schedule-maintenance-issues").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PERSISTED_INCOMPLETE"))
                .andExpect(jsonPath("$.links.issueId").value("90")).andExpect(jsonPath("$.links.scheduleRef").doesNotExist())
                .andExpect(jsonPath("$.appointmentImpact").doesNotExist());
    }

    @Test void successfulRevisionAcceptsPreviousIssueAndOnlyReturnsAValidCommittedImpact() throws Exception {
        var draft=JSON.createObjectNode();
        draft.putObject("scheduleRef").put("scheduleId","80").put("versionNo",2);
        when(service.revise(any(),eq(80L),any())).thenReturn(new CommandRecovery.Receipt("ref","SUCCEEDED",
                CommandRecovery.Persistence.CONFIRMED,draft,true,"5",null));
        write("/schedules/80/draft-revisions").andExpect(status().isAccepted())
                .andExpect(jsonPath("$.appointmentImpact").doesNotExist()).andExpect(jsonPath("$.links").doesNotExist());
        draft.putObject("appointmentImpact").putArray("appointmentIds");
        mvc.perform(post("/api/v1/staff/schedules/80/draft-revisions").header("Idempotency-Key","opaque")
                        .header("X-CSRF-Token","csrf").contentType(MediaType.APPLICATION_JSON).content("{\"previousIssueId\":\"91\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.existingResult").value(true))
                .andExpect(jsonPath("$.appointmentImpact.appointmentIds").isEmpty());
        verify(service).revise(any(),eq(80L),argThat(n->"91".equals(n.path("previousIssueId").asText())));
    }

    @Test void unknownAndPreCommitUncertaintyKeepDifferentResponses() throws Exception {
        var unknown=new CommandRecovery.Receipt("stable-ref","UNKNOWN",CommandRecovery.Persistence.UNKNOWN,null,false);
        when(service.create(any(),any())).thenReturn(unknown);
        when(service.revise(any(),eq(80L),any())).thenReturn(unknown);
        when(service.recordIssue(any(),any())).thenReturn(unknown);
        write("/schedule-drafts").andExpect(status().isAccepted()).andExpect(jsonPath("$.links").doesNotExist())
                .andExpect(jsonPath("$.appointmentImpact").doesNotExist());
        write("/schedules/80/draft-revisions").andExpect(status().isAccepted()).andExpect(jsonPath("$.requestReference").value("stable-ref"))
                .andExpect(jsonPath("$.appointmentImpact").doesNotExist());
        write("/schedule-maintenance-issues").andExpect(status().isServiceUnavailable())
                .andExpect(header().doesNotExist("Retry-After"))
                .andExpect(jsonPath("$.code").value("COMMIT_OUTCOME_UNKNOWN"))
                .andExpect(jsonPath("$.requestReference").value("stable-ref")).andExpect(jsonPath("$.links").doesNotExist())
                .andExpect(jsonPath("$.appointmentImpact").doesNotExist());
        when(service.recordIssue(any(),any())).thenReturn(new CommandRecovery.Receipt("stable-ref","UNKNOWN",
                CommandRecovery.Persistence.UNKNOWN,null,false,null,"WORK_ROLLBACK_UNCONFIRMED"));
        write("/schedule-maintenance-issues").andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("WORK_ROLLBACK_UNCONFIRMED"));
    }

    @Test void rolledBackDraftNeverReturnsSuccessLinksOrImpact() throws Exception {
        when(service.revise(any(),eq(80L),any())).thenReturn(new CommandRecovery.Receipt("stable-ref","FAILED",
                CommandRecovery.Persistence.NOT_PERSISTED,null,false));
        write("/schedules/80/draft-revisions").andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("OPERATION_FAILED"))
                .andExpect(jsonPath("$.links").doesNotExist())
                .andExpect(jsonPath("$.appointmentImpact").doesNotExist());
    }

    @Test void staleAndUnauthenticatedOrBadCsrfAreDenied() throws Exception{
        when(service.revise(any(),eq(80L),any())).thenReturn(new CommandRecovery.Receipt("ref","REJECTED",
                CommandRecovery.Persistence.CONFIRMED,null,false,null,"STALE_VERSION"));
        write("/schedules/80/draft-revisions").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STALE_VERSION"));
        mvc.perform(post("/api/v1/staff/schedule-drafts").header("Idempotency-Key","opaque")
                .header("X-CSRF-Token","wrong").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
        verify(service,never()).create(any(),any());
        when(access.identity(any())).thenReturn(new StaffSessionIdentityAdapter(()->null));
        mvc.perform(get("/api/v1/staff/schedules")).andExpect(status().isUnauthorized());
    }

    @Test void keysAreOpaqueCaseSensitiveAndNotRegeneratedOnDuplicate() throws Exception{
        when(service.create(any(),any())).thenReturn(new CommandRecovery.Receipt("ref","REJECTED",
                CommandRecovery.Persistence.CONFIRMED,null,true));
        write("/schedule-drafts").andExpect(status().isConflict());
        write("/schedule-drafts").andExpect(status().isConflict());
        mvc.perform(post("/api/v1/staff/schedule-drafts").header("Idempotency-Key","Opaque")
                .header("X-CSRF-Token","csrf").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isConflict());
        var keys=org.mockito.ArgumentCaptor.forClass(java.util.UUID.class);
        verify(service,times(3)).create(keys.capture(),any());
        org.junit.jupiter.api.Assertions.assertEquals(keys.getAllValues().get(0),keys.getAllValues().get(1));
        org.junit.jupiter.api.Assertions.assertNotEquals(keys.getAllValues().get(0),keys.getAllValues().get(2));
    }

    @Test void activationRequiresAnExplicitOptInAndAConfiguredTrustedBridge(){
        var runner=new ApplicationContextRunner().withUserConfiguration(ScheduleController.class);
        runner.run(c->org.assertj.core.api.Assertions.assertThat(c).doesNotHaveBean(ScheduleController.class));
        var enabled=runner.withPropertyValues("adc.uc21.enabled=true");
        enabled.run(c->org.assertj.core.api.Assertions.assertThat(c).hasFailed());
        enabled.withBean(ScheduleAccess.class,()->access)
                .run(c->org.assertj.core.api.Assertions.assertThat(c).hasSingleBean(ScheduleController.class));
    }

    private org.springframework.test.web.servlet.ResultActions write(String path) throws Exception {
        return mvc.perform(post("/api/v1/staff"+path).header("Idempotency-Key","opaque")
                .header("X-CSRF-Token","csrf").contentType(MediaType.APPLICATION_JSON).content("{}"));
    }
}
