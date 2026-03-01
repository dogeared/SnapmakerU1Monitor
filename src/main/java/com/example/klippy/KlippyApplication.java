package com.example.klippy;

import com.vaadin.flow.component.page.AppShellConfigurator;
import com.vaadin.flow.component.page.Push;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
@Push
public class KlippyApplication implements AppShellConfigurator {

    public static void main(String[] args) {
        SpringApplication.run(KlippyApplication.class, args);
    }
}
