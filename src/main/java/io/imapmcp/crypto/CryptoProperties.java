package io.imapmcp.crypto;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "imapmcp.crypto")
public class CryptoProperties {

    /** Base URL of the OpenBao server, e.g. {@code http://openbao:8200}. */
    private String openbaoAddress;

    /** Token used to authenticate to OpenBao (sent as the {@code X-Vault-Token} header). */
    private String openbaoToken;

    /** Name of the OpenBao Transit key used to wrap per-record DEKs. */
    private String transitKeyName;

    public String getOpenbaoAddress() {
        return openbaoAddress;
    }

    public void setOpenbaoAddress(String openbaoAddress) {
        this.openbaoAddress = openbaoAddress;
    }

    public String getOpenbaoToken() {
        return openbaoToken;
    }

    public void setOpenbaoToken(String openbaoToken) {
        this.openbaoToken = openbaoToken;
    }

    public String getTransitKeyName() {
        return transitKeyName;
    }

    public void setTransitKeyName(String transitKeyName) {
        this.transitKeyName = transitKeyName;
    }
}
