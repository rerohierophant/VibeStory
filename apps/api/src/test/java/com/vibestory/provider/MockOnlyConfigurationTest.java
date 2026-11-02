package com.vibestory.provider;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class MockOnlyConfigurationTest {
    @Test void onlyAllowsAllThreeMockProviders() {
        assertThatCode(() -> MockOnlyConfiguration.requireMock("mock", "mock", "mock")).doesNotThrowAnyException();
        assertThatThrownBy(() -> MockOnlyConfiguration.requireMock("openai-compatible", "mock", "mock")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> MockOnlyConfiguration.requireMock("mock", "minimax", "mock")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> MockOnlyConfiguration.requireMock("mock", "mock", "minimax")).isInstanceOf(IllegalStateException.class);
    }
}
