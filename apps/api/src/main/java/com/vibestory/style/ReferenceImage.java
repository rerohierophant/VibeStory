package com.vibestory.style;

import com.vibestory.common.ApiException;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

@Component
public class ReferenceImage {
    public record Encoded(byte[] bytes, String extension, String mediaType) {}

    public Encoded read(MultipartFile file) {
        if (file.isEmpty() || file.getSize() > 5 * 1024 * 1024) throw ApiException.badRequest("参考图须为 1 字节至 5 MiB");
        String claimed = file.getContentType();
        if (!"image/jpeg".equals(claimed) && !"image/png".equals(claimed))
            throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "参考图支持 JPEG / PNG");
        try (var input = file.getInputStream(); var stream = ImageIO.createImageInputStream(input)) {
            if (stream == null) throw ApiException.badRequest("无法读取参考图");
            var readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) throw ApiException.badRequest("参考图文件头或内容无效");
            var reader = readers.next();
            try {
                reader.setInput(stream, true, true);
                String format = reader.getFormatName().toLowerCase(java.util.Locale.ROOT);
                String actual = switch (format) { case "jpeg", "jpg" -> "image/jpeg"; case "png" -> "image/png"; default -> ""; };
                if (!actual.equals(claimed)) throw ApiException.badRequest("参考图类型与文件内容不一致");
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width < 1 || height < 1 || width > 4096 || height > 4096 || (long) width * height > 16_000_000)
                    throw ApiException.badRequest("参考图最长边不超过 4096 像素，总像素不超过 1600 万");
                BufferedImage source = reader.read(0);
                double scale = Math.min(1, 480.0 / Math.max(width, height));
                var resized = new BufferedImage(Math.max(1, (int) Math.round(width * scale)),
                    Math.max(1, (int) Math.round(height * scale)), actual.equals("image/png")
                        ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
                var graphics = resized.createGraphics();
                try {
                    graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                    graphics.drawImage(source, 0, 0, resized.getWidth(), resized.getHeight(), null);
                } finally { graphics.dispose(); source.flush(); }
                var bytes = new ByteArrayOutputStream();
                String extension = actual.equals("image/png") ? "png" : "jpg";
                if (!ImageIO.write(resized, extension, bytes)) throw ApiException.badRequest("无法编码参考图");
                resized.flush();
                return new Encoded(bytes.toByteArray(), extension, actual);
            } finally { reader.dispose(); }
        } catch (IOException | IllegalArgumentException error) {
            throw ApiException.badRequest("参考图无法解码");
        }
    }
}
