package cn.adc.appointments;

import java.util.Optional;

/** Internal translation for a future SCH-OP-013 transport adapter. */
final class BookingReviewFeedback {
    record Error(String code, String message) {}
    record Response(int status, Error body) {}

    private BookingReviewFeedback() {}

    static Optional<Response> forRejectedReview(String code) {
        if ("STALE_SCHEDULE".equals(code)) {
            return Optional.of(new Response(409, new Error(code, "接诊安排已变化，请重新匹配并取得申请人确认。")));
        }
        if ("STALE_DURATION_BASIS".equals(code)) {
            return Optional.of(new Response(409, new Error(code, "项目时长配置已更新，请重新匹配并取得申请人确认。")));
        }
        return Optional.empty();
    }
}
