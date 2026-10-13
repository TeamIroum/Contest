package kr.iroum.inspection.media;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.io.TempDir;

/** 파일 시스템 구현(기본값)의 저장소 계약. */
class FileSystemMediaStorageContractTest extends MediaStorageContractTest {

    @TempDir
    Path root;

    @Override
    MediaStorage storage() {
        return new FileSystemMediaStorage(root.toString());
    }

    @Override
    byte[] rawObject(String bucket, String key) {
        Path p = root.resolve(bucket).resolve(key);
        try {
            return Files.exists(p) ? Files.readAllBytes(p) : null;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
