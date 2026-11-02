package com.vibestory.provider;

import org.springframework.stereotype.Component;

@Component
public class ImageProviders {
    private final ImageProperties config;
    private final MockImageProvider mock;
    private final MiniMaxImageProvider remote;
    public ImageProviders(ImageProperties config, MockImageProvider mock, MiniMaxImageProvider remote) {
        this.config = config; this.mock = mock; this.remote = remote;
    }
    public ImageProvider.Result generate(ImageProvider.Input input, String signature) throws InterruptedException {
        if (input.provider().equals("mock")) return mock.generate(input, signature);
        if (!input.provider().equals(config.provider()) || !input.endpoint().replaceAll("/+$", "").equals(config.baseUrl().replaceAll("/+$", "")))
            throw new ProviderException("IMAGE_CONFIGURATION_CHANGED", "任务提交后的图片接口配置已变化，请重新提交", false, false);
        return remote.generate(input, signature);
    }
    public ImageProvider.Capabilities capabilities(String provider) {
        return switch(provider) {
            case "mock" -> new ImageProvider.Capabilities(false,true,Integer.MAX_VALUE,true,Integer.MAX_VALUE);
            case "minimax" -> new ImageProvider.Capabilities(false,false,1,false,1500);
            default -> throw new IllegalArgumentException("未知图片适配器");
        };
    }
    public boolean safeReplay(String provider) { return capabilities(provider).idempotencyKeySupported(); }
}
