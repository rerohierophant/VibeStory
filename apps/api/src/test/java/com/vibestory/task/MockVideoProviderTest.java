package com.vibestory.task;

import com.vibestory.provider.MockVideoProvider;
import com.vibestory.provider.ProviderException;
import com.vibestory.provider.VideoProvider;
import com.vibestory.storage.LocalStorage;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.*;

class MockVideoProviderTest {
    @TempDir Path temporary;
    private final VideoProvider.Request request = new VideoProvider.Request(1, 1, 1, 1, 1, "1234567890abcdef", "{}");

    @Test void knownFailureIsExplicitlyRetryable() {
        var provider = provider("known");
        assertThatThrownBy(() -> provider.generate(request)).isInstanceOfSatisfying(ProviderException.class, error -> {
            assertThat(error.code()).isEqualTo("MOCK_REJECTED");
            assertThat(error.retryable()).isTrue();
            assertThat(error.unknown()).isFalse();
        });
    }

    @Test void unknownOutcomeIsNotAutomaticallyRetryable() {
        var provider = provider("unknown");
        assertThatThrownBy(() -> provider.generate(request)).isInstanceOfSatisfying(ProviderException.class, error -> {
            assertThat(error.code()).isEqualTo("MOCK_RESULT_UNKNOWN");
            assertThat(error.retryable()).isFalse();
            assertThat(error.unknown()).isTrue();
        });
    }

    private MockVideoProvider provider(String mode) {
        return new MockVideoProvider(new MockVideoProvider.Properties(Duration.ZERO, 1, mode), new LocalStorage(temporary.toString()));
    }
}
