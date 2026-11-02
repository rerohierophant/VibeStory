package com.vibestory.provider;

import org.springframework.stereotype.Component;

@Component
public class TextProviders {
    private final MockTextProvider mock;
    private final OpenAiCompatibleTextProvider compatible;
    private final TextProperties properties;
    private final ShotDraftValidation validation;

    public TextProviders(MockTextProvider mock, OpenAiCompatibleTextProvider compatible, TextProperties properties, ShotDraftValidation validation) {
        this.mock = mock; this.compatible = compatible; this.properties = properties; this.validation = validation;
    }

    public TextProvider.Result generate(TextProvider.Input input) throws InterruptedException {
        if (input.provider().equals("mock")) {
            var result = mock.generate(input);
            try { validation.validate(result.shots()); }
            catch (IllegalArgumentException | com.vibestory.common.ApiException error) {
                throw new ProviderException("MOCK_TEXT_INVALID_OUTPUT", "Mock 拆解为空或超过字段限制，请调整正文", false, false);
            }
            return result;
        }
        // Credentials are resolved at execution time, but never silently send them to a changed endpoint.
        if (!input.provider().equals("openai-compatible") || !properties.provider().equals(input.provider()) ||
                !properties.baseUrl().replaceAll("/+$", "").equals(input.endpoint().replaceAll("/+$", "")))
            throw new ProviderException("TEXT_CONFIGURATION_CHANGED", "任务提交后的文本接口配置已变化，请重新提交", false, false);
        return compatible.generate(input);
    }

    public TextProvider.Capabilities capabilities(String provider) {
        return switch (provider) {
            case "mock" -> mock.capabilities();
            case "openai-compatible" -> compatible.capabilities();
            default -> throw new IllegalArgumentException("未知文本适配器");
        };
    }
}
