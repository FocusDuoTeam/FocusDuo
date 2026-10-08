package dev.focusduo.config;

import java.util.UUID;
import org.springframework.context.annotation.Configuration;
import org.springframework.format.FormatterRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {
    @Override public void addFormatters(FormatterRegistry registry) {
        registry.addConverter(String.class, UUID.class, source -> {
            UUID value = UUID.fromString(source);
            if (!value.toString().equalsIgnoreCase(source)) throw new IllegalArgumentException("Expected UUID");
            return value;
        });
    }
}
