package kr.iroum.inspection.media;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 파일 시스템 사진 저장소(기본값, inspection.media.store=filesystem). 로컬 개발·테스트와 S3 전환 전 단일 WAS 운영용이다.
 * 루트는 저장소 밖 경로로 설정한다. 위치는 루트/논리 버킷/키다.
 * 쓰기는 같은 디렉터리의 임시 이름에 쓴 뒤 원자적으로 옮겨, 중간 장애에 반쯤 쓴 파일이 조회되지 않게 한다.
 */
@Component
@ConditionalOnProperty(name = "inspection.media.store", havingValue = "filesystem", matchIfMissing = true)
public class FileSystemMediaStorage implements MediaStorage {

    private final Path root;

    public FileSystemMediaStorage(@Value("${inspection.media.storage-root:${java.io.tmpdir}/itda-inspection-media}") String root) {
        this.root = Path.of(root).toAbsolutePath().normalize();
    }

    @Override
    public void put(String bucket, String key, byte[] bytes) {
        Path target = resolve(bucket, key);
        try {
            Files.createDirectories(target.getParent());
            Path tmp = target.resolveSibling(".tmp-" + UUID.randomUUID());
            Files.write(tmp, bytes);
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("사진 저장 실패", e);
        }
    }

    @Override
    public byte[] read(String bucket, String key) {
        try {
            return Files.readAllBytes(resolve(bucket, key));
        } catch (IOException e) {
            throw new UncheckedIOException("사진 읽기 실패", e);
        }
    }

    @Override
    public void deleteQuietly(String bucket, String key) {
        try {
            Files.deleteIfExists(resolve(bucket, key));
        } catch (IOException ignored) {
            // 고아 정리는 다음 정리 작업에 맡긴다.
        }
    }

    private Path resolve(String bucket, String key) {
        MediaStorage.checkLocation(bucket, key);
        Path p = root.resolve(bucket).resolve(key).normalize();
        if (!p.startsWith(root.resolve(bucket))) {
            throw new IllegalArgumentException("저장 경로 오류");
        }
        return p;
    }
}
