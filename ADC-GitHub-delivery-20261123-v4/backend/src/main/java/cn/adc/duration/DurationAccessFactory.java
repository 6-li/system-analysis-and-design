package cn.adc.duration;
import cn.adc.shared.config.*;import cn.adc.shared.security.*;import cn.adc.shared.db.JdbcTransactions;import jakarta.servlet.http.*;import java.util.*;import java.util.function.Supplier;import java.time.Clock;
/** No default bean or fake login. A reviewed server source must explicitly install this bridge. */
public final class DurationAccessFactory implements DurationAccess {
 private final JdbcTransactions tx;private final StaffBindingAdapter bindings;private final Supplier<SourceStamp> activation;private final Clock clock;
 public DurationAccessFactory(JdbcTransactions tx,StaffBindingAdapter bindings,Supplier<SourceStamp> activation,Clock clock){this.tx=tx;this.bindings=bindings;this.activation=activation;this.clock=clock;}
 public StaffSessionIdentityAdapter identity(HttpServletRequest request){var cached=request.getAttribute("ADC_DURATION_REQUEST_IDENTITY");if(cached instanceof StaffSessionIdentityAdapter a)return a;var adapter=new StaffSessionIdentityAdapter(()->{var session=request.getSession(false);var attribute=session==null?null:session.getAttribute(StaffSessionIdentityAdapter.SESSION_ATTRIBUTE);return attribute instanceof StaffSessionIdentityAdapter.VerifiedSession v?v:null;});request.setAttribute("ADC_DURATION_REQUEST_IDENTITY",adapter);return adapter;}
 public DurationPolicyService service(HttpServletRequest request){try{activation.get().requireUsable();}catch(RuntimeException e){throw new ConfigurationUnavailable();}var identity=identity(request);if(identity.current().isEmpty())throw new SecurityException("AUTHENTICATION_REQUIRED");return new DurationPolicyService(tx,new StaffAuthorization(identity,bindings::bindings),clock);}
}
