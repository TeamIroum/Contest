package kr.iroum.inspection.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import kr.iroum.inspection.media.MediaStorage;

/** 서버 검사 완료 사진 저장소(04단계 공백 G1의 파일 시스템 구현): 원자적 쓰기, 덮어쓰기, 저장소 밖 경로 차단. */
class MediaStorageTest {

    @TempDir
    Path root;

    @Test
    void writesAtomicallyAndLeavesNoTemporaryFiles() throws IOException {
        MediaStorage storage = new MediaStorage(root.toString());
        storage.put(MediaStorage.BUCKET, "p1/a.jpg", new byte[] {1, 2, 3});
        storage.put(MediaStorage.BUCKET, "p1/a.jpg", new byte[] {4, 5});
        assertThat(storage.read(MediaStorage.BUCKET, "p1/a.jpg")).containsExactly(4, 5);
        try (Stream<Path> files = Files.walk(root)) {
            assertThat(files.filter(Files::isRegularFile).map(p -> p.getFileName().toString())).containsExactly("a.jpg");
        }
        storage.deleteQuietly(MediaStorage.BUCKET, "p1/a.jpg");
        assertThatCode(() -> storage.deleteQuietly(MediaStorage.BUCKET, "p1/a.jpg")).doesNotThrowAnyException();
        assertThatThrownBy(() -> storage.read(MediaStorage.BUCKET, "p1/a.jpg")).isInstanceOf(UncheckedIOException.class);
    }

    @Test
    void rejectsKeysOutsideTheBucket() {
        MediaStorage storage = new MediaStorage(root.toString());
        for (String key : new String[] {"../x.jpg", "p1/../../x.jpg", "/etc/passwd"}) {
            assertThatThrownBy(() -> storage.put(MediaStorage.BUCKET, key, new byte[] {1})).as(key)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
