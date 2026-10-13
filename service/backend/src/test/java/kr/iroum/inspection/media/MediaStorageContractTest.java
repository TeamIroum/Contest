package kr.iroum.inspection.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.UncheckedIOException;

import org.junit.jupiter.api.Test;

/**
 * 사진 저장소 계약. 파일 시스템·S3 구현이 같은 결과를 내야 호출자(사진 접수·조회·재가림·보류 해제)가 저장소와 관계없이 같게 동작한다.
 * 같은 키를 다시 쓰지 않으므로(키에 UUID) 덮어쓰기는 계약에 넣지 않는다.
 */
abstract class MediaStorageContractTest {

    /** 테스트마다 비어 있는 저장소. */
    abstract MediaStorage storage();

    /** 구현이 실제로 보관한 바이트(저장소 우회 확인). 없으면 null. */
    abstract byte[] rawObject(String bucket, String key);

    @Test
    void putThenReadReturnsSameBytes() {
        MediaStorage s = storage();
        byte[] bytes = {1, 2, 3, 4};
        s.put(MediaStorage.BUCKET, "p1/i1/r1-a.jpg", bytes);
        assertThat(s.read(MediaStorage.BUCKET, "p1/i1/r1-a.jpg")).containsExactly(1, 2, 3, 4);
        assertThat(rawObject(MediaStorage.BUCKET, "p1/i1/r1-a.jpg")).containsExactly(1, 2, 3, 4);
        s.put(MediaStorage.BUCKET, "p1/remask/insp_x/r2-b.png", new byte[] {9});
        assertThat(s.read(MediaStorage.BUCKET, "p1/remask/insp_x/r2-b.png")).containsExactly(9);
    }

    @Test
    void missingKeyIsReadFailure() {
        assertThatThrownBy(() -> storage().read(MediaStorage.BUCKET, "p1/none.jpg")).isInstanceOf(UncheckedIOException.class);
    }

    @Test
    void deleteRemovesObjectAndIgnoresMissing() {
        MediaStorage s = storage();
        s.put(MediaStorage.BUCKET, "p1/d.jpg", new byte[] {5});
        s.deleteQuietly(MediaStorage.BUCKET, "p1/d.jpg");
        assertThat(rawObject(MediaStorage.BUCKET, "p1/d.jpg")).isNull();
        assertThatThrownBy(() -> s.read(MediaStorage.BUCKET, "p1/d.jpg")).isInstanceOf(UncheckedIOException.class);
        assertThatCode(() -> s.deleteQuietly(MediaStorage.BUCKET, "p1/d.jpg")).doesNotThrowAnyException();
    }

    @Test
    void rejectsKeysLeavingTheBucket() {
        MediaStorage s = storage();
        String[] bad = {"../x.jpg", "p1/../../x.jpg", "p1/../x.jpg", "/etc/passwd", "p1\\..\\x.jpg", "p1//x.jpg", "./x.jpg", "p1/x.jpg/..",
                "", "C:/x.jpg", "p1/\u0000.jpg"};
        for (String key : bad) {
            assertThatThrownBy(() -> s.put(MediaStorage.BUCKET, key, new byte[] {1})).as(key).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> s.read(MediaStorage.BUCKET, key)).as(key).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> s.deleteQuietly(MediaStorage.BUCKET, key)).as(key).isInstanceOf(IllegalArgumentException.class);
        }
        for (String bucket : new String[] {"..", "a/b", "", "."}) {
            assertThatThrownBy(() -> s.put(bucket, "p1/x.jpg", new byte[] {1})).as(bucket).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
