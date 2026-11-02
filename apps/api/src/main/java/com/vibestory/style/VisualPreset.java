package com.vibestory.style;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(name = "VisualPreset")
public record VisualPreset(String id, String category, String label, String hint, String image,
                           String imagePosition, List<String> tone, boolean custom) {}
