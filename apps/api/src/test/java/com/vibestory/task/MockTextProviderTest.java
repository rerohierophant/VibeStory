package com.vibestory.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vibestory.provider.MockTextProvider;
import com.vibestory.provider.TextProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import static org.assertj.core.api.Assertions.assertThat;

class MockTextProviderTest {
    @TestFactory Stream<DynamicTest> matchesActualFrontendSplitter() throws Exception {
        var json = new ObjectMapper();
        var input = getClass().getResourceAsStream("/splitter-cases.json");
        var cases = json.readTree(input);
        var result = new ArrayList<DynamicTest>();
        for (var fixture : cases) {
            result.add(DynamicTest.dynamicTest(fixture.get("name").asText(), () -> {
                List<TextProvider.SceneOption> scenes = fixture.has("scenes")
                    ? json.readerForListOf(TextProvider.SceneOption.class).readValue(fixture.get("scenes")) : List.of();
                var snapshot = new TextProvider.Input("BREAKDOWN", 1, "mock", "https://api.deepseek.com", "mock-splitter-v1",
                    "breakdown-v1", 0, 0, fixture.get("story").asText(), scenes, 8192, true);
                var actual = new MockTextProvider().generate(snapshot).shots();
                var expected = fixture.get("expected").deepCopy();
                expected.forEach(item -> ((ObjectNode) item).putArray("characterNames"));
                com.fasterxml.jackson.databind.JsonNode actualJson = json.valueToTree(actual);
                assertThat(actualJson).isEqualTo(expected);
            }));
        }
        return result.stream();
    }
}
