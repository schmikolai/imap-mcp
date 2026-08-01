package io.imapmcp.config;

import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.imapmcp.ratelimit.RateLimitFilter;
import io.imapmcp.ratelimit.RateLimitProperties;
import io.imapmcp.tenant.TenantContextFilter;
import io.imapmcp.tenant.TenantUserRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * First-party human login (signup/consent screens). This is a distinct
 * identity and filter chain from the {@code /mcp/**} chain
 * ({@link McpSecurityConfig}) that authenticates AI agents — the two must
 * never share a session/credential model. Ordered after it (higher number =
 * lower precedence) and left unmatched (applies to everything else) since
 * {@code /mcp/**} is always claimed by the other chain first.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    @Order(2)
    public SecurityFilterChain webSecurityFilterChain(HttpSecurity http, TenantUserRepository tenantUserRepository,
                                                        ProxyManager<String> bucket4jProxyManager,
                                                        RateLimitProperties rateLimitProperties) throws Exception {
        RequestMatcher rateLimitedEndpoints = new OrRequestMatcher(
                new AntPathRequestMatcher("/login", HttpMethod.POST.name()),
                new AntPathRequestMatcher("/signup", HttpMethod.POST.name()));
        // Per IP — brute-force/account-enumeration throttle on the
        // first-party human login/signup forms, ahead of authentication.
        RateLimitFilter rateLimitFilter = new RateLimitFilter(
                key -> bucket4jProxyManager.builder().build("rl:web:" + key,
                        rateLimitProperties.getWebAuth()::toBucketConfiguration),
                HttpServletRequest::getRemoteAddr,
                rateLimitedEndpoints);

        http
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/signup", "/login", "/css/**", "/actuator/health", "/error",
                                "/.well-known/oauth-protected-resource",
                                "/.well-known/oauth-protected-resource/mcp").permitAll()
                        .anyRequest().authenticated())
                .formLogin(form -> form
                        .loginPage("/login")
                        // Without this, Spring Security's default post-login
                        // redirect goes to "/" whenever there's no saved
                        // request (e.g. the user opened /login directly
                        // rather than being bounced there from a protected
                        // page) — and this app has no controller mapped to
                        // "/" at all, so that 404s via static-resource
                        // fallback ("No static resource .").
                        .defaultSuccessUrl("/accounts")
                        .permitAll())
                .logout(logout -> logout.permitAll())
                .headers(headers -> headers
                        .frameOptions(frameOptions -> frameOptions.deny()))
                .addFilterAfter(new TenantContextFilter(tenantUserRepository), AuthorizationFilter.class)
                .addFilterBefore(rateLimitFilter, AuthorizationFilter.class);

        return http.build();
    }
}
