package com.vibestory.style;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

@Component
public class VisualCatalog {
    private final List<VisualPreset> presets;

    public VisualCatalog(ObjectMapper json) throws IOException {
        try (var input = new ClassPathResource("visual-presets.json").getInputStream()) {
            presets = List.copyOf(json.readerForListOf(VisualPreset.class).readValue(input));
        }
    }
    public List<VisualPreset> list() { return presets; }
    public Optional<VisualPreset> find(String id) { return presets.stream().filter(item -> item.id().equals(id)).findFirst(); }
}
