package kr.iroum.inspection.support;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.testcontainers.localstack.LocalStackContainer;
import org.testcontainers.utility.DockerImageName;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * 테스트 전용 S3 호환 컨테이너(LocalStack, 태그 고정). 실제 AWS S3·IAM 검증이 아니다.
 * 컨테이너의 시험용 자격 증명은 테스트 코드에서만 쓴다(운영 설정에는 접근 키를 두지 않는다).
 * 한 테스트 실행에서 컨테이너 하나를 같이 쓰고 테스트마다 다른 버킷을 만든다. 종료는 Testcontainers 정리 컨테이너가 맡는다.
 */
public final class LocalS3 {

    public static final String IMAGE = "localstack/localstack:4.4.0";

    private static LocalStackContainer container;

    private LocalS3() {
    }

    public static synchronized LocalStackContainer container() {
        if (container == null) {
            LocalStackContainer c = new LocalStackContainer(DockerImageName.parse(IMAGE)).withServices("s3", "kms");
            c.start();
            container = c;
        }
        return container;
    }

    /** 컨테이너 자격 증명을 쓰는 테스트용 클라이언트. 운영 코드는 기본 자격 증명 체인만 쓴다. */
    public static S3Client client() {
        LocalStackContainer c = container();
        return S3Client.builder().endpointOverride(c.getEndpoint()).forcePathStyle(true).region(Region.of(c.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(c.getAccessKey(), c.getSecretKey())))
                .build();
    }

    public static String createBucket(S3Client s3) {
        String bucket = "itda-test-" + UUID.randomUUID().toString().substring(0, 8);
        s3.createBucket(b -> b.bucket(bucket));
        return bucket;
    }

    public static List<String> keys(S3Client s3, String bucket) {
        List<String> keys = new ArrayList<>();
        s3.listObjectsV2Paginator(r -> r.bucket(bucket)).contents().forEach(o -> keys.add(o.key()));
        return keys;
    }

    public static void emptyAndDelete(S3Client s3, String bucket) {
        List<ObjectIdentifier> ids = new ArrayList<>();
        for (S3Object o : s3.listObjectsV2Paginator(r -> r.bucket(bucket)).contents()) {
            ids.add(ObjectIdentifier.builder().key(o.key()).build());
        }
        if (!ids.isEmpty()) {
            s3.deleteObjects(r -> r.bucket(bucket).delete(d -> d.objects(ids)));
        }
        s3.deleteBucket(r -> r.bucket(bucket));
    }
}
