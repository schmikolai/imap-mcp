package io.imapmcp.imap;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "imapmcp.imap")
public class ImapProperties {

    private int connectTimeoutMs = 10_000;
    private int readTimeoutMs = 15_000;
    private int maxConnectionsPerAccount = 2;
    private int lockoutThreshold = 5;
    private int lockoutBackoffSeconds = 300;

    public int getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public void setConnectTimeoutMs(int connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }

    public int getReadTimeoutMs() {
        return readTimeoutMs;
    }

    public void setReadTimeoutMs(int readTimeoutMs) {
        this.readTimeoutMs = readTimeoutMs;
    }

    public int getMaxConnectionsPerAccount() {
        return maxConnectionsPerAccount;
    }

    public void setMaxConnectionsPerAccount(int maxConnectionsPerAccount) {
        this.maxConnectionsPerAccount = maxConnectionsPerAccount;
    }

    public int getLockoutThreshold() {
        return lockoutThreshold;
    }

    public void setLockoutThreshold(int lockoutThreshold) {
        this.lockoutThreshold = lockoutThreshold;
    }

    public int getLockoutBackoffSeconds() {
        return lockoutBackoffSeconds;
    }

    public void setLockoutBackoffSeconds(int lockoutBackoffSeconds) {
        this.lockoutBackoffSeconds = lockoutBackoffSeconds;
    }
}
