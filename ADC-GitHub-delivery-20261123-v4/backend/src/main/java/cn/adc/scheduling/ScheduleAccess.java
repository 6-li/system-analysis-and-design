package cn.adc.scheduling;

import cn.adc.shared.security.StaffSessionIdentityAdapter;
import jakarta.servlet.http.HttpServletRequest;

/** A reviewed server-side deployment must explicitly supply this bridge. */
public interface ScheduleAccess {
    StaffSessionIdentityAdapter identity(HttpServletRequest request);
    ScheduleOperations service(HttpServletRequest request);
}
