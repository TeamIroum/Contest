package kr.iroum.inspection.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.UncheckedIOException;
import java.net.URI;
import java.time.Duration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import kr.iroum.inspection.support.LocalS3;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;

/**
 * S3 구현의 저장소 계약과 S3 고유 동작(키 대응·암호화·Content-Type·실패 처리)을 S3 호환 컨테이너(LocalStack)로 확인한다.
 * 실제 AWS S3·IAM·버킷 정책 검증이 아니다.
 */
class S3MediaStorageContractTest extends MediaStorageContractTest {

    static final String PREFIX = "itda/test";

    S3Client s3;
    String bucket;

    @BeforeEach
    void bucket() {
        s3 = LocalS3.client();
        bucket = LocalS3.createBucket(s3);
    }

    @AfterEach
    void cleanup() {
        LocalS3.emptyAndDelete(s3, bucket);
        s3.close();
    }

    @Override
    MediaStorage storage() {
        return new S3MediaStorage(s3, bucket, PREFIX, "AES256", "");
    }

    @Override
    byte[] rawObject(String logicalBucket, String key) {
        try {
            return s3.getObjectAsBytes(r -> r.bucket(bucket).key(PREFIX + "/" + logicalBucket + "/" + key)).asByteArray();
        } catch (NoSuchKeyException e) {
            return null;
        }
    }

    @Test
    void logicalBucketMapsToPrefixAndObjectsAreEncryptedWithContentType() {
        MediaStorage s = new S3MediaStorage(s3, bucket, "/itda/test/", "AES256", "");
        s.put(MediaStorage.BUCKET, "p1/i1/r1-a.jpg", new byte[] {1});
        s.put(MediaStorage.BUCKET, "p1/i1/r1-b.png", new byte[] {2});
        assertThat(LocalS3.keys(s3, bucket)).containsExactlyInAnyOrder("itda/test/inspection-media/p1/i1/r1-a.jpg",
                "itda/test/inspection-media/p1/i1/r1-b.png");
        HeadObjectResponse jpg = s3.headObject(r -> r.bucket(bucket).key("itda/test/inspection-media/p1/i1/r1-a.jpg"));
        assertThat(jpg.serverSideEncryption()).isEqualTo(ServerSideEncryption.AES256);
        assertThat(jpg.contentType()).isEqualTo("image/jpeg");
        assertThat(s3.headObject(r -> r.bucket(bucket).key("itda/test/inspection-media/p1/i1/r1-b.png")).contentType()).isEqualTo("image/png");
        // 접두사가 없으면 버킷 바로 아래 논리 이름(IAM 리소스 arn:aws:s3:::<버킷>/inspection-media/*와 맞음)
        new S3MediaStorage(s3, bucket, "", null, null).put(MediaStorage.BUCKET, "p2/c.jpg", new byte[] {3});
        assertThat(LocalS3.keys(s3, bucket)).contains("inspection-media/p2/c.jpg");
        // 공개 읽기 권한(ACL)을 주지 않는다: 객체 ACL에 그룹(AllUsers 등) 권한이 없다.
        assertThat(s3.getObjectAcl(r -> r.bucket(bucket).key("inspection-media/p2/c.jpg")).grants())
                .allSatisfy(g -> assertThat(g.grantee().uri()).isNull());
    }

    @Test
    void kmsEncryptionCanBeSelected() {
        new S3MediaStorage(s3, bucket, "", "aws:kms", "").put(MediaStorage.BUCKET, "p1/k.jpg", new byte[] {7});
        assertThat(s3.headObject(r -> r.bucket(bucket).key("inspection-media/p1/k.jpg")).serverSideEncryption())
                .isEqualTo(ServerSideEncryption.AWS_KMS);
    }

    @Test
    void invalidSettingsFailAtStartup() {
        assertThatThrownBy(() -> new S3MediaStorage(s3, " ", "", "AES256", "")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new S3MediaStorage(s3, bucket, "", "none", "")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new S3MediaStorage(s3, bucket, "a/../b", "AES256", "")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void missingBucketOrUnreachableEndpointIsStorageFailureAndDeleteIsIgnored() {
        MediaStorage noBucket = new S3MediaStorage(s3, bucket + "-missing", "", "AES256", "");
        assertThatThrownBy(() -> noBucket.put(MediaStorage.BUCKET, "p1/a.jpg", new byte[] {1})).isInstanceOf(UncheckedIOException.class)
                .hasMessage("사진 저장 실패").cause().hasMessageStartingWith("S3 HTTP 404");
        assertThatThrownBy(() -> noBucket.read(MediaStorage.BUCKET, "p1/a.jpg")).isInstanceOf(UncheckedIOException.class);
        assertThatCode(() -> noBucket.deleteQuietly(MediaStorage.BUCKET, "p1/a.jpg")).doesNotThrowAnyException();

        try (S3Client down = S3Client.builder().endpointOverride(URI.create("http://127.0.0.1:1")).forcePathStyle(true)
                .region(Region.AP_NORTHEAST_2).credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("x", "y")))
                .overrideConfiguration(o -> o.apiCallTimeout(Duration.ofSeconds(5))).build()) {
            MediaStorage unreachable = new S3MediaStorage(down, bucket, "", "AES256", "");
            assertThatThrownBy(() -> unreachable.put(MediaStorage.BUCKET, "p1/a.jpg", new byte[] {1})).isInstanceOf(UncheckedIOException.class);
            assertThatThrownBy(() -> unreachable.read(MediaStorage.BUCKET, "p1/a.jpg")).isInstanceOf(UncheckedIOException.class);
            assertThatCode(() -> unreachable.deleteQuietly(MediaStorage.BUCKET, "p1/a.jpg")).doesNotThrowAnyException();
        }
    }
}
