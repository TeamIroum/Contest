package kr.iroum.inspection.media;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Locale;

import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;

/**
 * S3 사진 저장소(inspection.media.store=s3). 논리 버킷 이름은 설정한 실제 버킷 안의 "접두사/논리 이름/키" 객체로 대응한다.
 * DB의 bucket·object_key 값은 그대로다.
 * <ul>
 * <li>put: PutObject에 서버 측 암호화(기본 SSE-S3 AES256, 설정 시 SSE-KMS)와 확장자 기준 Content-Type을 붙인다. ACL은 보내지 않는다.</li>
 * <li>read: GetObject를 바이트로 읽는다. 없음(404)·거부(403, ListBucket 권한이 없으면 없음도 403)·연결 실패는 모두 UncheckedIOException.</li>
 * <li>deleteQuietly: DeleteObject. 실패는 무시한다.</li>
 * </ul>
 * 미리 서명한 URL은 만들지 않는다. 사진 조회는 요청마다 권한·공개 상태를 확인한 뒤 WAS가 바이트로 응답한다.
 * 실패 원인은 상태 코드·S3 오류 코드만 남기고 키·버킷 이름은 예외 메시지에 넣지 않는다.
 */
public class S3MediaStorage implements MediaStorage {

    private final S3Client s3;
    private final String bucket;
    private final String prefix;
    private final ServerSideEncryption encryption;
    private final String kmsKeyId;

    /**
     * @param prefix 객체 키 앞에 붙일 접두사. 비어 있으면 "논리 이름/키". 앞뒤 '/'는 무시한다.
     * @param encryption "AES256"(SSE-S3) 또는 "aws:kms"(SSE-KMS)
     * @param kmsKeyId SSE-KMS일 때 쓸 키. 비어 있으면 S3 관리 키(aws/s3)
     */
    public S3MediaStorage(S3Client s3, String bucket, String prefix, String encryption, String kmsKeyId) {
        if (bucket == null || bucket.isBlank()) {
            throw new IllegalStateException("inspection.media.s3.bucket 설정이 필요합니다.");
        }
        this.s3 = s3;
        this.bucket = bucket.trim();
        this.prefix = normalizePrefix(prefix);
        this.encryption = ServerSideEncryption.fromValue(encryption == null || encryption.isBlank() ? "AES256" : encryption.trim());
        if (this.encryption != ServerSideEncryption.AES256 && this.encryption != ServerSideEncryption.AWS_KMS) {
            throw new IllegalStateException("inspection.media.s3.server-side-encryption은 AES256 또는 aws:kms만 허용합니다.");
        }
        this.kmsKeyId = kmsKeyId == null || kmsKeyId.isBlank() ? null : kmsKeyId.trim();
    }

    @Override
    public void put(String logicalBucket, String key, byte[] bytes) {
        String objectKey = objectKey(logicalBucket, key);
        PutObjectRequest.Builder req = PutObjectRequest.builder().bucket(bucket).key(objectKey).contentType(contentTypeOf(key))
                .serverSideEncryption(encryption);
        if (encryption == ServerSideEncryption.AWS_KMS && kmsKeyId != null) {
            req.ssekmsKeyId(kmsKeyId);
        }
        try {
            s3.putObject(req.build(), RequestBody.fromBytes(bytes));
        } catch (SdkException e) {
            throw failure("사진 저장 실패", e);
        }
    }

    @Override
    public byte[] read(String logicalBucket, String key) {
        String objectKey = objectKey(logicalBucket, key);
        try {
            return s3.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(objectKey).build()).asByteArray();
        } catch (SdkException e) {
            throw failure("사진 읽기 실패", e);
        }
    }

    @Override
    public void deleteQuietly(String logicalBucket, String key) {
        String objectKey = objectKey(logicalBucket, key);
        try {
            s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(objectKey).build());
        } catch (SdkException ignored) {
            // 고아 정리는 다음 정리 작업에 맡긴다.
        }
    }

    /** 실제 S3 객체 키. 테스트·이전 작업에서 같은 대응을 쓰도록 공개한다. */
    public String objectKey(String logicalBucket, String key) {
        MediaStorage.checkLocation(logicalBucket, key);
        return prefix + logicalBucket + "/" + key;
    }

    private static String normalizePrefix(String prefix) {
        if (prefix == null) {
            return "";
        }
        String p = prefix.strip();
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        while (p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        if (p.isEmpty()) {
            return "";
        }
        // 접두사도 키와 같은 규칙(경로 이탈·역슬래시 금지)을 따른다.
        MediaStorage.checkLocation("prefix", p);
        return p + "/";
    }

    private static String contentTypeOf(String key) {
        String k = key.toLowerCase(Locale.ROOT);
        if (k.endsWith(".png")) {
            return "image/png";
        }
        if (k.endsWith(".jpg") || k.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        return "application/octet-stream";
    }

    private static UncheckedIOException failure(String message, SdkException e) {
        String detail = e instanceof S3Exception s ? "HTTP " + s.statusCode()
                + (s.awsErrorDetails() != null && s.awsErrorDetails().errorCode() != null ? " " + s.awsErrorDetails().errorCode() : "")
                : e.getClass().getSimpleName();
        return new UncheckedIOException(message, new IOException("S3 " + detail, e));
    }
}
