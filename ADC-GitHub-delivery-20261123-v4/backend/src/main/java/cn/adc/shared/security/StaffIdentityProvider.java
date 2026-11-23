package cn.adc.shared.security;
import java.util.Optional;
/** Server-trusted adapter only; no header/body fallback or built-in demo identity. */
@FunctionalInterface public interface StaffIdentityProvider {Optional<String> current();}
