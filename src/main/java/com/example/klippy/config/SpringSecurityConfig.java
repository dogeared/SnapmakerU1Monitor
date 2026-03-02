package com.example.klippy.config;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler;
import org.springframework.security.web.authentication.logout.SimpleUrlLogoutSuccessHandler;

@Configuration
@EnableWebSecurity
public class SpringSecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        String[] allowedPaths = {"/VAADIN/**"};

        http
            .headers(headers -> headers.frameOptions(options -> options.sameOrigin()))
            .authorizeHttpRequests(requests -> requests
                .requestMatchers(allowedPaths).permitAll()
                .requestMatchers("/admin/**").hasRole("ADMIN")
                .anyRequest().authenticated())
            .csrf(csrf -> csrf
                .ignoringRequestMatchers(allowedPaths)
                .ignoringRequestMatchers(SpringSecurityConfig::isVaadinInternalRequest)
            )
            .oauth2Login(oauth2 -> oauth2
                .defaultSuccessUrl("/", true)
                .failureUrl("/?error=true")
            )
            .logout(logout -> logout
                .logoutSuccessHandler(logoutSuccessHandler())
                .logoutSuccessUrl("/")
                .invalidateHttpSession(true)
                .clearAuthentication(true)
                .deleteCookies("JSESSIONID"));

        return http.build();
    }

    @Bean
    public LogoutSuccessHandler logoutSuccessHandler() {
        SimpleUrlLogoutSuccessHandler handler = new SimpleUrlLogoutSuccessHandler();
        handler.setDefaultTargetUrl("/");
        return handler;
    }


    private static boolean isVaadinInternalRequest(HttpServletRequest request) {
        String paramValue = request.getParameter("v-r");
        return paramValue != null && (
            paramValue.equals("uidl") ||
            paramValue.equals("heartbeat") ||
            paramValue.equals("push")
        );
    }
}
