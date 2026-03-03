package com.example.klippy.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * Activates the "secure" Spring profile (loading application-secure.properties)
 * only when global.security.enabled is not "false".
 * Registered via META-INF/spring.factories.
 */
public class SecurityProfileConfig implements EnvironmentPostProcessor {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String securityEnabled = environment.getProperty("global.security.enabled", "true");
        if (!"false".equalsIgnoreCase(securityEnabled)) {
            environment.addActiveProfile("secure");
        }
    }
}
