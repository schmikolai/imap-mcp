package io.imapmcp.crypto;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "imapmcp.crypto")
public class CryptoProperties {

    /** ARN or alias of the KMS Customer Master Key used to wrap per-record DEKs. */
    private String kmsKeyId;

    private String awsRegion;

    public String getKmsKeyId() {
        return kmsKeyId;
    }

    public void setKmsKeyId(String kmsKeyId) {
        this.kmsKeyId = kmsKeyId;
    }

    public String getAwsRegion() {
        return awsRegion;
    }

    public void setAwsRegion(String awsRegion) {
        this.awsRegion = awsRegion;
    }
}
