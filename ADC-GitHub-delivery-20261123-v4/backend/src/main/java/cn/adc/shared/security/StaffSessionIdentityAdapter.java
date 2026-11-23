package cn.adc.shared.security;
import cn.adc.shared.config.SourceStamp;import java.util.*;import java.util.function.Supplier;import java.security.MessageDigest;import java.nio.charset.StandardCharsets;
/** Only a trusted server bridge may supply this typed session. No login/header fallback. */
public final class StaffSessionIdentityAdapter implements StaffIdentityProvider {
 public static final String SESSION_ATTRIBUTE="ADC_VERIFIED_STAFF_SESSION";
 public record VerifiedSession(String subject,String csrfToken,SourceStamp provenance){}
 private final Supplier<VerifiedSession> sessions;private String boundSubject;public StaffSessionIdentityAdapter(Supplier<VerifiedSession> sessions){this.sessions=sessions;}
 private synchronized VerifiedSession verified(){try{var s=sessions.get();if(s==null||s.subject()==null||s.subject().isBlank()||!s.subject().equals(s.subject().strip())||s.subject().length()>100)return null;s.provenance().requireUsable();if(boundSubject==null)boundSubject=s.subject();if(!boundSubject.equals(s.subject()))return null;return s;}catch(RuntimeException e){return null;}}
 public Optional<String> current(){var s=verified();return s==null?Optional.empty():Optional.of(s.subject());}
 public String csrfToken(){var s=verified();if(s==null)throw new SecurityException("AUTHENTICATION_REQUIRED");if(s.csrfToken()==null||s.csrfToken().isBlank())throw new SecurityException("CSRF_REJECTED");return s.csrfToken();}
 public void requireCsrf(String supplied){var s=verified();if(s==null)throw new SecurityException("AUTHENTICATION_REQUIRED");if(supplied==null||s.csrfToken()==null||s.csrfToken().isBlank()||!MessageDigest.isEqual(supplied.getBytes(StandardCharsets.UTF_8),s.csrfToken().getBytes(StandardCharsets.UTF_8)))throw new SecurityException("CSRF_REJECTED");}
}
