package cn.adc.classroom;

import cn.adc.shared.config.SourceStamp;
import cn.adc.shared.security.StaffSessionIdentityAdapter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.net.URI;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.springframework.http.MediaType.TEXT_HTML;

/** Course-only session bridge. Business authorization remains in the staff adapters. */
@Controller
@Profile("course-demo")
final class CourseStaffLogin {
    private static final Map<String, Destination> ACCOUNTS = Map.of(
            "reception", new Destination("SYNTHETIC reception", "/staff/appointments"),
            "dentist", new Destination("SYNTHETIC dentist", "/staff/visit-consent"),
            "billing", new Destination("SYNTHETIC billing", "/staff/treatment-invoice"),
            "procurement", new Destination("SYNTHETIC procurement", "/staff/purchase-requirements"),
            "manager", new Destination("SYNTHETIC manager", "/staff/stock-inquiry"),
            "operations", new Destination("SYNTHETIC operations", "/staff/quarterly-preview"),
            "finance", new Destination("SYNTHETIC finance", "/staff/quarterly-financial-review"));

    private final Map<String, String> passwords;
    private final String origin;
    private final boolean uiUnification;
    private final Clock clock = Clock.systemUTC();

    CourseStaffLogin(Environment environment) {
        uiUnification=environment.getProperty("adc.course.ui-unification-enabled",Boolean.class,false);
        origin = environment.getRequiredProperty("adc.course.public-origin");
        var uri = URI.create(origin);
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getRawUserInfo() != null
                || !uri.getRawPath().isEmpty() || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalArgumentException("Course public origin must be an HTTPS origin");
        }
        var configured = new HashMap<String, String>();
        for (String account : ACCOUNTS.keySet()) {
            String password = environment.getRequiredProperty("ADC_DEMO_" + account.toUpperCase(Locale.ROOT));
            if (password.isBlank() || configured.containsValue(password)) {
                throw new IllegalArgumentException("Course accounts require distinct non-empty passwords");
            }
            configured.put(account, password);
        }
        passwords = Map.copyOf(configured);
    }

    @GetMapping(value = "/", produces = "text/html;charset=UTF-8")
    ResponseEntity<String> page() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(new MediaType(TEXT_HTML, UTF_8)).body("""
                <!doctype html><html lang="zh-CN"><meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>ADC 牙科工作台</title>
                <style>
                *{box-sizing:border-box}body{margin:0;background:#f4f7f5;color:#24463b;font:15px/1.7 "Microsoft YaHei",sans-serif}main{min-height:100vh;display:grid;grid-template-columns:1fr 1fr;max-width:1200px;margin:auto;align-items:center;gap:80px;padding:60px}.login-intro .mark{font-weight:800;letter-spacing:4px;color:#327a5f}.login-intro h1{font-size:42px;line-height:1.4;margin:24px 0}.login-intro p{color:#769083}.login-topics{display:flex;gap:10px;flex-wrap:wrap;margin-top:32px}.login-topics span{padding:7px 12px;border:1px solid #d5e4d9;border-radius:8px;font-size:12px;color:#5e806c}.login-card{padding:42px;background:white;border:1px solid #e0e8e1;border-radius:22px;box-shadow:0 16px 60px #264b3810}.login-card h2{font-size:25px;margin:0 0 8px}.login-card>p{color:#899b90;font-size:13px;margin:0 0 30px}label{display:grid;gap:8px;font-size:13px;font-weight:600}input{width:100%;padding:13px 15px;border:1px solid #cfddd3;border-radius:9px;font:inherit;font-weight:400;min-height:48px}form p{margin:20px 0}button{width:100%;min-height:48px;background:#217364;color:white;border:0;border-radius:9px;font:inherit;cursor:pointer;margin-top:12px}input:focus-visible,button:focus-visible{outline:3px solid #a5c9b4;outline-offset:2px}.login-foot{margin-top:24px;color:#9bac9f;font-size:11px;text-align:center}@media(max-width:760px){main{display:block;padding:36px 20px}.login-intro{max-width:480px;margin:0 auto 30px}.login-intro h1{font-size:29px}.login-topics{display:none}.login-card{max-width:480px;margin:auto;padding:28px}}
                </style>
                <main><div class="login-intro"><div class="mark">ADC · DENTAL</div><h1>让每一次接诊<br>井然有序。</h1><p>连接预约、诊疗与运营，<br>在你的岗位工作台，专注当前工作。</p><div class="login-topics"><span>预约与接诊</span><span>诊疗与收费</span><span>库存与运营</span></div></div>
                <div class="login-card"><h2>登录工作台</h2><p>使用员工账号，进入对应岗位。</p>
                <form method="post" action="/demo/login">
                <p><label>员工账号 <input name="account" placeholder="请输入员工账号" autocomplete="username" required></label></p>
                <p><label>密码 <input name="password" placeholder="请输入密码" type="password" autocomplete="current-password" required></label></p>
                <button type="submit">登录</button>
                </form><div class="login-foot">ADC 牙科连锁诊所 · 课程演示环境</div></div></main></html>
                """);
    }

    @PostMapping("/demo/login")
    ResponseEntity<Void> login(HttpServletRequest request, @RequestParam String account, @RequestParam String password) {
        if (!origin.equals(request.getHeader("Origin"))) {
            return ResponseEntity.status(403).cacheControl(CacheControl.noStore()).build();
        }
        String expected = passwords.get(account);
        if (expected == null || !MessageDigest.isEqual(expected.getBytes(UTF_8), password.getBytes(UTF_8))) {
            return ResponseEntity.status(401).cacheControl(CacheControl.noStore()).build();
        }
        var old = request.getSession(false);
        if (old != null) old.invalidate();
        var destination = ACCOUNTS.get(account);
        request.getSession(true).setAttribute(StaffSessionIdentityAdapter.SESSION_ATTRIBUTE,
                new StaffSessionIdentityAdapter.VerifiedSession(destination.subject(), UUID.randomUUID().toString(),
                        new SourceStamp("SYNTHETIC", "course-demo", "course", clock.instant(), true)));
        return ResponseEntity.status(303).cacheControl(CacheControl.noStore())
                .location(URI.create(uiUnification&&"billing".equals(account)?"/staff/billing-workbench":destination.path())).build();
    }

    private record Destination(String subject, String path) {}
}
