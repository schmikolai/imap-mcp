package io.imapmcp.web;

import io.imapmcp.tenant.ImapAccount;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public class LinkImapAccountForm {

    @NotBlank
    private String displayName;

    @NotBlank
    private String host;

    @NotNull
    @Min(1)
    @Max(65535)
    private Integer port = 993;

    @NotNull
    private ImapAccount.TlsMode tlsMode = ImapAccount.TlsMode.IMPLICIT;

    @NotBlank
    private String username;

    @NotBlank
    private String password;

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public Integer getPort() {
        return port;
    }

    public void setPort(Integer port) {
        this.port = port;
    }

    public ImapAccount.TlsMode getTlsMode() {
        return tlsMode;
    }

    public void setTlsMode(ImapAccount.TlsMode tlsMode) {
        this.tlsMode = tlsMode;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }
}
