package com.mindbridge;

import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

@Configuration
public class SecurityConfig {
    @Bean UserDetailsService users(@Value("${mindbridge.auth.student-password:student-demo}") String student,
            @Value("${mindbridge.auth.second-password:student2-demo}") String second,
            @Value("${mindbridge.auth.admin-password:admin-demo}") String admin) {
        var encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
        return new InMemoryUserDetailsManager(
            User.withUsername("student").password(encoder.encode(student)).roles("STUDENT").build(),
            User.withUsername("student2").password(encoder.encode(second)).roles("STUDENT").build(),
            User.withUsername("admin").password(encoder.encode(admin)).roles("ADMIN").build());
    }
    @Bean SecurityFilterChain security(HttpSecurity http) throws Exception {
        // Session-backed CSRF stays enabled, including login and logout.
        http.authorizeHttpRequests(auth -> auth
            .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
            .requestMatchers("/login.html", "/login.js", "/styles.css", "/api/csrf").permitAll()
            .requestMatchers("/api/admin/**", "/api/alerts/**").hasRole("ADMIN")
            .requestMatchers("/api/conversations/**").hasRole("STUDENT")
            .anyRequest().authenticated())
            .formLogin(form -> form.loginPage("/login.html").loginProcessingUrl("/login")
                .successHandler((req,res,auth)->res.setStatus(204))
                .failureHandler((req,res,error)->res.sendError(401,"Invalid credentials")))
            .logout(logout -> logout.logoutUrl("/logout").logoutSuccessHandler((req,res,auth)->res.setStatus(204)))
            .exceptionHandling(errors -> errors.defaultAuthenticationEntryPointFor(
                (req,res,error)->res.sendError(401),new AntPathRequestMatcher("/api/**")));
        return http.build();
    }
}
