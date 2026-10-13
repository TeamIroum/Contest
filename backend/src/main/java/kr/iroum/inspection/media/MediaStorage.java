package kr.iroum.inspection.media;

/**
 * 업무 사진 저장소. 서버 개인정보 검사를 마친 사본(AI가 가린 사진)만 저장한다. 검사 전 바이트는 이 저장소를 거치지 않는다.
 * 구현은 inspection.media.store로 고른다: filesystem(기본, {@link FileSystemMediaStorage}) 또는 s3({@link S3MediaStorage}).
 * DB media.bucket에는 논리 이름 {@link #BUCKET}을 저장하고, 실제 위치(디렉터리·S3 버킷과 접두사)는 구현이 정한다.
 * <ul>
 * <li>put·read는 저장소 실패를 {@link java.io.UncheckedIOException}으로 알린다. 호출자는 구현과 관계없이 같은 방식으로 처리한다.</li>
 * <li>저장소 밖을 가리키는 키(경로 이탈·절대 경로·역슬래시 등)는 {@link IllegalArgumentException}으로 거부한다.</li>
 * <li>사진은 업무 API(GET /media/{id}/content)가 권한을 확인한 뒤 바이트로만 내보낸다. 공개 주소·미리 서명한 URL은 만들지 않는다.</li>
 * </ul>
 */
public interface MediaStorage {

    String BUCKET = "inspection-media";

    void put(String bucket, String key, byte[] bytes);

    byte[] read(String bucket, String key);

    /** 커밋되지 않은 결과의 고아 사본 정리용. 없거나 지우지 못해도 오류가 아니다(다음 정리 작업에 맡긴다). */
    void deleteQuietly(String bucket, String key);

    /**
     * 논리 버킷 이름과 키를 검사한다. 버킷은 경로 구분자 없는 한 단계, 키는 상대 경로이며 빈·"."·".." 단계, 역슬래시, 제어 문자를 허용하지 않는다.
     * 두 구현이 같은 규칙을 쓰므로 저장소를 바꿔도 거부되는 키가 같다.
     */
    static void checkLocation(String bucket, String key) {
        if (bucket == null || bucket.isEmpty() || bucket.contains("/") || !validSegments(bucket)) {
            throw new IllegalArgumentException("저장 경로 오류");
        }
        if (key == null || key.isEmpty() || key.length() > 512 || key.startsWith("/") || !validSegments(key)) {
            throw new IllegalArgumentException("저장 경로 오류");
        }
    }

    private static boolean validSegments(String path) {
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == '\\' || c == ':' || Character.isISOControl(c)) {
                return false;
            }
        }
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                return false;
            }
        }
        return true;
    }
}
