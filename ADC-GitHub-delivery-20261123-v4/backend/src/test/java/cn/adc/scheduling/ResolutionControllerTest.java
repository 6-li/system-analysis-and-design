package cn.adc.scheduling;

import cn.adc.shared.audit.AuditDocuments;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ResolutionControllerTest {
    @Test void activationExposesOnlyConfirmedVersionAndChecksCsrf() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new ResolutionController(new ResolutionController.HistoryAccess() {
            public tools.jackson.databind.JsonNode read(jakarta.servlet.http.HttpServletRequest request, long id, int version) {
                throw new AssertionError("unexpected read");
            }
            public void requireCsrf(jakarta.servlet.http.HttpServletRequest request, String csrf) {
                if (!"csrf".equals(csrf)) throw new SecurityException("CSRF_DENIED");
            }
            public cn.adc.shared.audit.CommandRecovery.Receipt activate(jakarta.servlet.http.HttpServletRequest request,
                    long id, int version, String key, tools.jackson.databind.JsonNode body) {
                org.junit.jupiter.api.Assertions.assertEquals("draft-token", body.path("expectedDraftToken").asText());
                var result = AuditDocuments.JSON.createObjectNode().put("scheduleId", Long.toString(id)).put("versionNo", version);
                return new cn.adc.shared.audit.CommandRecovery.Receipt("original", "SUCCEEDED",
                        cn.adc.shared.audit.CommandRecovery.Persistence.CONFIRMED, result, false, "100", null);
            }
        })).build();
        var path = "/api/v1/staff/schedules/7/versions/2/activations";
        mvc.perform(post(path).header("Idempotency-Key", "key").header("X-CSRF-Token", "wrong")
                .contentType("application/json").content("{\"expectedDraftToken\":\"draft-token\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post(path).header("Idempotency-Key", "key").header("X-CSRF-Token", "csrf")
                .contentType("application/json").content("{\"expectedDraftToken\":\"draft-token\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.operationKind").value("ACTIVATE"))
                .andExpect(jsonPath("$.links.scheduleRef.scheduleId").value("7"))
                .andExpect(jsonPath("$.links.scheduleRef.versionNo").value(2));
    }
    @Test void saveReturnsOnlyConfirmedResolutionLinksAndPreservesUnknownRequest() throws Exception {
        var receipt = new java.util.concurrent.atomic.AtomicReference<cn.adc.shared.audit.CommandRecovery.Receipt>();
        var mvc = MockMvcBuilders.standaloneSetup(new ResolutionController(new ResolutionController.HistoryAccess() {
            public tools.jackson.databind.JsonNode read(jakarta.servlet.http.HttpServletRequest request, long id, int version) {
                throw new AssertionError("unexpected read");
            }
            public void requireCsrf(jakarta.servlet.http.HttpServletRequest request, String csrf) {
                if (!"test-csrf".equals(csrf)) throw new SecurityException();
            }
            public cn.adc.shared.audit.CommandRecovery.Receipt save(jakarta.servlet.http.HttpServletRequest request,
                    long id, int version, String key, tools.jackson.databind.JsonNode body) {
                return java.util.Objects.requireNonNull(receipt.get());
            }
        })).build();
        var request = post("/api/v1/staff/schedules/7/versions/2/resolutions")
                .header("Idempotency-Key", "test").header("X-CSRF-Token", "test-csrf")
                .contentType("application/json").content("{}");
        var result = AuditDocuments.JSON.createObjectNode().put("resolution_id", "21");
        receipt.set(new cn.adc.shared.audit.CommandRecovery.Receipt("original", "SUCCEEDED",
                cn.adc.shared.audit.CommandRecovery.Persistence.CONFIRMED, result, false, "99", null));
        mvc.perform(request).andExpect(status().isOk()).andExpect(jsonPath("$.links.resolutionId").value("21"))
                .andExpect(jsonPath("$.operationKind").value("RESOLUTION_SAVE"));
        receipt.set(new cn.adc.shared.audit.CommandRecovery.Receipt("original", "SUCCEEDED",
                cn.adc.shared.audit.CommandRecovery.Persistence.CONFIRMED, result, false));
        mvc.perform(request).andExpect(status().isAccepted()).andExpect(jsonPath("$.requestReference").value("original"))
                .andExpect(jsonPath("$.status").value("UNKNOWN")).andExpect(jsonPath("$.links").doesNotExist());
        receipt.set(new cn.adc.shared.audit.CommandRecovery.Receipt("original", "REJECTED",
                cn.adc.shared.audit.CommandRecovery.Persistence.CONFIRMED, null, false, null, "RESOLUTION_HEAD_STALE"));
        mvc.perform(request).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESOLUTION_HEAD_STALE"))
                .andExpect(jsonPath("$.requestReference").value("original"));
        receipt.set(new cn.adc.shared.audit.CommandRecovery.Receipt("original", "FAILED",
                cn.adc.shared.audit.CommandRecovery.Persistence.NOT_PERSISTED, null, false));
        mvc.perform(request).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("OPERATION_FAILED"))
                .andExpect(jsonPath("$.requestReference").value("original"));
    }

    private static final String PATH = "/api/v1/staff/schedules/{id}/versions/{version}/resolution-histories";

    @Test void impactRouteReturnsPreviewWithoutClaimingDraftIsEffectiveOrLeakingOtherClinic() throws Exception {
        var instant = java.time.Instant.parse("2026-11-14T00:00:00Z");
        var target = new ImpactAssessmentService.Schedule(7, 2, 1, 9,
                new ImpactAssessmentService.Window(instant, instant.plusSeconds(60)));
        var impact = new ImpactAssessmentService.Impact(10, 7, 1, ImpactAssessmentService.Assessment.SUPPORTED);
        var preview = new ImpactAssessmentService.Preview(target,
                List.of(new ImpactAssessmentService.ObservedImpact(impact, "observed-token")), true);
        var mvc = MockMvcBuilders.standaloneSetup(new ResolutionController(new ResolutionController.HistoryAccess() {
            public tools.jackson.databind.JsonNode read(jakarta.servlet.http.HttpServletRequest request, long id, int version) {
                throw new AssertionError("wrong route");
            }
            public tools.jackson.databind.JsonNode impact(jakarta.servlet.http.HttpServletRequest request, long id, int version) {
                return ImpactReportView.of(preview, instant);
            }
        })).build();
        mvc.perform(get("/api/v1/staff/schedules/7/versions/2/impacts")).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.targetRef.versionNo").value(2))
                .andExpect(jsonPath("$.items[0].support.result").value("UNRESOLVED"))
                .andExpect(jsonPath("$.items[0].support.effectiveScheduleRef").doesNotExist())
                .andExpect(jsonPath("$.hiddenConflicts[0].visibleScheduleRef").doesNotExist());
    }

    @Test void historyReturnsEveryVisibleRootAndDisablesCaching() throws Exception {
        var snapshot = AuditDocuments.JSON.createObjectNode().put("resolution_id", "10");
        var next = AuditDocuments.JSON.createObjectNode().put("resolution_id", "11");
        var other = AuditDocuments.JSON.createObjectNode().put("resolution_id", "12");
        var body = ResolutionController.historyBody(List.of(
                new ResolutionHistoryReadService.History(10, 11, "head-token", List.of(snapshot, next)),
                new ResolutionHistoryReadService.History(12, 12, "other-token", List.of(other))));
        var mvc = MockMvcBuilders.standaloneSetup(new ResolutionController((request, id, version) -> body)).build();
        mvc.perform(get(PATH, 7, 2)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].originalRecord.resolution_id").value("10"))
                .andExpect(jsonPath("$[0].revisions[0].resolution_id").value("11"))
                .andExpect(jsonPath("$[0].currentAssessment").value("PENDING"))
                .andExpect(jsonPath("$[1].rootResolutionId").value("12"));
    }

    @Test void unauthorizedHistoryAndInvalidPathsDoNotReturnEvidence() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new ResolutionController((request, id, version) -> {
            throw new SecurityException("ACTION_OR_SCOPE_DENIED");
        })).build();
        mvc.perform(get(PATH, 7, 2)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.originalRecord").doesNotExist());
        mvc.perform(get(PATH, 7, -1)).andExpect(status().isBadRequest());
    }
    @Test void impactCheckRequiresHeadersAndCsrfBeforeReading() throws Exception {
        var reads = new java.util.concurrent.atomic.AtomicInteger();
        var mvc = MockMvcBuilders.standaloneSetup(new ResolutionController(new ResolutionController.HistoryAccess() {
            public tools.jackson.databind.JsonNode read(jakarta.servlet.http.HttpServletRequest request, long id, int version) {
                throw new AssertionError("unexpected history read");
            }
            public tools.jackson.databind.JsonNode impact(jakarta.servlet.http.HttpServletRequest request, long id, int version) {
                reads.incrementAndGet();
                return AuditDocuments.JSON.createObjectNode().put("checkToken", "observed");
            }
            public void requireCsrf(jakarta.servlet.http.HttpServletRequest request, String token) {
                if (!"session-csrf".equals(token)) throw new SecurityException("CSRF_DENIED");
            }
        })).build();
        String path = "/api/v1/staff/schedules/7/versions/2/impact-checks";
        mvc.perform(post(path)).andExpect(status().isBadRequest());
        mvc.perform(post(path).header("Idempotency-Key", "test").header("X-CSRF-Token", "wrong"))
                .andExpect(status().isForbidden());
        org.junit.jupiter.api.Assertions.assertEquals(0, reads.get());
        mvc.perform(post(path).header("Idempotency-Key", "test").header("X-CSRF-Token", "session-csrf"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.checkToken").value("observed"));
        org.junit.jupiter.api.Assertions.assertEquals(1, reads.get());
    }
}
