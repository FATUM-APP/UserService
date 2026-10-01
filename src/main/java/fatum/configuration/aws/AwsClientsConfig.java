package fatum.configuration.aws;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.rekognition.RekognitionClient;
import software.amazon.awssdk.services.textract.TextractClient;

/**
 * AWS clients used by the identity verification pipeline and by the Cognito group management.
 *
 * <p>S3 is deliberately absent: file storage is delegated to the shared storage service, so this
 * microservice never needs bucket permissions.</p>
 */
@Configuration
public class AwsClientsConfig {

    @Bean
    public TextractClient textractClient(@Value("${aws.region}") String region) {
        return TextractClient.builder()
                .region(Region.of(region))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
    }

    @Bean
    public RekognitionClient rekognitionClient(@Value("${aws.region}") String region) {
        return RekognitionClient.builder()
                .region(Region.of(region))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
    }

    @Bean
    public BedrockRuntimeClient bedrockRuntimeClient(@Value("${aws.region}") String region) {
        return BedrockRuntimeClient.builder()
                .region(Region.of(region))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
    }

    @Bean
    public CognitoIdentityProviderClient cognitoIdentityProviderClient(@Value("${aws.region}") String region) {
        return CognitoIdentityProviderClient.builder()
                .region(Region.of(region))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
    }
}
