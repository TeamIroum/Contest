package kr.iroum.inspection.inspection;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Iterator;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import kr.iroum.inspection.common.error.ApiException;
import kr.iroum.inspection.common.error.ErrorCode;

/**
 * 사진 파일 서버 검사(업무 API 6.3절): 실제 형식(앞 바이트), 디코딩, 픽셀 수. 요청의 Content-Type은 믿지 않는다.
 * 검사 전 바이트는 메모리에서만 다룬다. ImageIO 디스크 캐시를 쓰지 않도록 메모리 캐시 스트림을 직접 만든다.
 * 픽셀 수는 머리 정보로 먼저 확인해 큰 사진을 전부 풀지 않고, 디코딩 확인은 표본 추출로 메모리를 줄인다.
 */
@Component
public class ImageInspector {

    public static final long MAX_BYTES = 10_485_760L;

    public record Checked(byte[] bytes, String contentType, int width, int height) {
    }

    private final long maxPixels;

    public ImageInspector(@Value("${inspection.photo.max-pixels:40000000}") long maxPixels) {
        this.maxPixels = maxPixels;
    }

    public Checked inspect(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "입력 내용을 확인해 주세요.", null,
                    java.util.List.of(new kr.iroum.inspection.common.error.ErrorResponse.FieldError("file", "REQUIRED", "사진 파일이 필요합니다.")));
        }
        if (bytes.length > MAX_BYTES) {
            throw tooLarge();
        }
        String type = sniff(bytes);
        if (type == null) {
            throw unsupported();
        }
        try (ImageInputStream in = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                throw unsupported();
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= 0 || height <= 0) {
                    throw unsupported();
                }
                if ((long) width * height > maxPixels) {
                    throw tooLarge();
                }
                ImageReadParam param = reader.getDefaultReadParam();
                int step = Math.max(1, (int) Math.ceil(Math.sqrt((double) width * height / 1_000_000d)));
                param.setSourceSubsampling(step, step, 0, 0);
                if (reader.read(0, param) == null) {
                    throw unsupported();
                }
                return new Checked(bytes, type, width, height);
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            if (e instanceof ApiException api) {
                throw api;
            }
            throw unsupported();
        }
    }

    /** JPEG(FF D8 FF)·PNG(89 50 4E 47 0D 0A 1A 0A)만 받는다. HEIC·AVIF·WebP 등은 null. */
    static String sniff(byte[] b) {
        if (b.length >= 3 && (b[0] & 0xff) == 0xFF && (b[1] & 0xff) == 0xD8 && (b[2] & 0xff) == 0xFF) {
            return "image/jpeg";
        }
        byte[] png = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
        if (b.length >= png.length && java.util.Arrays.equals(java.util.Arrays.copyOf(b, png.length), png)) {
            return "image/png";
        }
        return null;
    }

    private static ApiException tooLarge() {
        return new ApiException(ErrorCode.PAYLOAD_TOO_LARGE, "사진 크기가 너무 커요.");
    }

    private static ApiException unsupported() {
        return new ApiException(ErrorCode.UNSUPPORTED_MEDIA_TYPE, "사진 형식을 확인해 주세요. 앱 카메라로 찍은 사진만 보낼 수 있어요.");
    }
}
