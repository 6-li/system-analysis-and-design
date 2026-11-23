package cn.adc.shared.config;

import java.net.URI;

/** Deployment-owned HTTPS origin, not a business permission or identity source. */
public final class CoursePublicOrigin {
    private CoursePublicOrigin() {}
    public static String require(String value) {
        if (value == null) throw new IllegalArgumentException("COURSE_HTTPS_ORIGIN_REQUIRED");
        var uri = URI.create(value);
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getRawUserInfo() != null
                || !uri.getRawPath().isEmpty() || uri.getRawQuery() != null || uri.getRawFragment() != null
                || uri.getPort() == 0 || uri.getPort() > 65535) {
            throw new IllegalArgumentException("COURSE_HTTPS_ORIGIN_REQUIRED");
        }
        return value;
    }
}
