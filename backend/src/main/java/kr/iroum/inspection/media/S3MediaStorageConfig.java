package kr.iroum.inspection.media;

import java.net.URI;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * inspection.media.store=s3일 때만 S3 클라이언트와 저장소를 만든다.
 * 자격 증명은 AWS SDK 기본 체인만 쓴다(AWS에서는 EC2 인스턴스 역할). 접근 키를 설정 파일에 두지 않는다.
 * endpoint는 로컬 S3 호환 시험 컨테이너용이다. 운영에서는 비워 두어 리전 기본 주소(VPC 게이트웨이 엔드포인트 경유)를 쓴다.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "inspection.media.store", havingValue = "s3")
public class S3MediaStorageConfig {

    @Bean(destroyMethod = "close")
    S3Client mediaS3Client(@Value("${inspection.media.s3.region:ap-northeast-2}") String region,
            @Value("${inspection.media.s3.endpoint:}") String endpoint) {
        var builder = S3Client.builder().region(Region.of(region)).credentialsProvider(DefaultCredentialsProvider.builder().build());
        if (!endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint)).forcePathStyle(true);
        }
        return builder.build();
    }

    @Bean
    MediaStorage s3MediaStorage(S3Client mediaS3Client, @Value("${inspection.media.s3.bucket:}") String bucket,
            @Value("${inspection.media.s3.prefix:}") String prefix,
            @Value("${inspection.media.s3.server-side-encryption:AES256}") String encryption,
            @Value("${inspection.media.s3.kms-key-id:}") String kmsKeyId) {
        return new S3MediaStorage(mediaS3Client, bucket, prefix, encryption, kmsKeyId);
    }
}
