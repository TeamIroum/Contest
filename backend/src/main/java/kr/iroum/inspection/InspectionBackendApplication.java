package kr.iroum.inspection;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class InspectionBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(InspectionBackendApplication.class, args);
    }
}
