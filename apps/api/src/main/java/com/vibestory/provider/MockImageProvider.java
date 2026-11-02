package com.vibestory.provider;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class MockImageProvider implements ImageProvider {
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private MockSubmissionProbe probe;
    private final java.time.Duration delay;
    public MockImageProvider(@Value("${vibestory.mock.delay:3s}") java.time.Duration delay) { this.delay = delay; }
    @Override public Result generate(Input input, String signature) throws InterruptedException {
        com.vibestory.admission.SubmissionBoundary.beforeSubmission();
        if(probe!=null) probe.entered("IMAGE");
        Thread.sleep(delay);
        int width = 640, height = input.aspectRatio().equals("1:1") ? 640 : 360;
        var bitmap = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var canvas = bitmap.createGraphics();
        try {
            canvas.setColor(new Color(Integer.parseInt(signature.substring(0, 6), 16)));
            canvas.fillRect(0, 0, width, height);
            canvas.setColor(Color.WHITE);
            canvas.drawString("VibeStory Mock / " + signature.substring(0, 12), 24, height / 2);
            var bytes = new ByteArrayOutputStream();
            ImageIO.write(bitmap, "png", bytes);
            return new Result(bytes.toByteArray(), "image/png", "mock-image-v1", "{\"simulated\":true,\"images\":1}");
        } catch (java.io.IOException error) { throw new IllegalStateException(error); }
        finally { canvas.dispose(); bitmap.flush(); }
    }
}
