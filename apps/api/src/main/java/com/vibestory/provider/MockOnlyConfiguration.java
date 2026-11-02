package com.vibestory.provider;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Fail at startup if a free verification process accidentally selects a paid adapter. */
@Component
@ConditionalOnProperty(name = "vibestory.mock-only", havingValue = "true")
public class MockOnlyConfiguration implements InitializingBean {
    private final TextProperties text;
    private final ImageProperties image;
    private final VideoProperties video;

    public MockOnlyConfiguration(TextProperties text, ImageProperties image, VideoProperties video) {
        this.text = text; this.image = image; this.video = video;
    }
    @Override public void afterPropertiesSet() {
        requireMock(text.provider(), image.provider(), video.provider());
    }
    static void requireMock(String text, String image, String video) {
        if (!"mock".equals(text) || !"mock".equals(image) || !"mock".equals(video))
            throw new IllegalStateException("Mock-only mode requires Mock text, image and video providers");
    }
}
