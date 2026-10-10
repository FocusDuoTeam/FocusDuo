package dev.focusduo.config;

import java.beans.PropertyEditorSupport;
import java.util.UUID;
import org.springframework.context.annotation.Configuration;
import org.springframework.format.FormatterRegistry;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@ControllerAdvice
public class WebConfig implements WebMvcConfigurer {
    @Override public void addFormatters(FormatterRegistry registry) {
        registry.addConverter(String.class, UUID.class, WebConfig::parseUuid);
    }

    @InitBinder
    void strictUuidBinding(WebDataBinder binder) {
        // A failing Converter alone is insufficient: Spring may fall back to its lenient UUIDEditor.
        binder.registerCustomEditor(UUID.class, new PropertyEditorSupport() {
            @Override public void setAsText(String text) { setValue(parseUuid(text)); }
        });
    }

    private static UUID parseUuid(String source) {
        UUID value = UUID.fromString(source);
        if (!value.toString().equalsIgnoreCase(source)) throw new IllegalArgumentException("Expected UUID");
        return value;
    }
}
