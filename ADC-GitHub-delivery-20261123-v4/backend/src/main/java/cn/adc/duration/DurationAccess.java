package cn.adc.duration;
import jakarta.servlet.http.HttpServletRequest;import cn.adc.shared.security.StaffSessionIdentityAdapter;
public interface DurationAccess {DurationOperations service(HttpServletRequest request);StaffSessionIdentityAdapter identity(HttpServletRequest request);}
