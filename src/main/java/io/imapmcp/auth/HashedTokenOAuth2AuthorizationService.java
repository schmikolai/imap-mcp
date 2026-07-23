package io.imapmcp.auth;

import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.core.OAuth2Token;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationCode;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;
import java.util.function.BiFunction;

/**
 * Persists authorization codes, access tokens and refresh tokens as SHA-256
 * hashes rather than raw values — a leak of the {@code oauth2_authorization}
 * table alone does not yield usable bearer tokens.
 *
 * <p>Works entirely through {@link OAuth2Authorization}'s public builder API
 * (rebuilding each token entry with a hashed value, keeping its original
 * metadata) rather than reaching into {@link JdbcOAuth2AuthorizationService}'s
 * internal SQL parameter mapping, so it stays stable across framework
 * versions. Safe because nothing in Spring Authorization Server's own flows
 * needs the raw value back out of a {@code findByToken}/{@code findById}
 * lookup — those calls only need to confirm a token is valid and load its
 * associated client/principal/scopes; the raw value a client sees is always
 * the one generated fresh at issuance time, before it ever reaches this
 * class.
 */
public class HashedTokenOAuth2AuthorizationService extends JdbcOAuth2AuthorizationService {

    /**
     * Only these token types are ever hashed on write (see
     * {@link #hashTokenValues}) — everything else (notably {@code state},
     * used to correlate the pre-consent authorization request) is stored raw
     * by the superclass and must be looked up raw too, or every consent
     * submission fails with "invalid_request: state" since the hashed
     * lookup value would never match the unhashed stored one.
     */
    private static final Set<String> HASHED_TOKEN_TYPE_VALUES = Set.of("code", "access_token", "refresh_token");

    public HashedTokenOAuth2AuthorizationService(JdbcOperations jdbcOperations,
                                                  RegisteredClientRepository registeredClientRepository) {
        super(jdbcOperations, registeredClientRepository);
    }

    @Override
    public void save(OAuth2Authorization authorization) {
        super.save(hashTokenValues(authorization));
    }

    @Override
    public OAuth2Authorization findByToken(String token, OAuth2TokenType tokenType) {
        if (tokenType != null && !HASHED_TOKEN_TYPE_VALUES.contains(tokenType.getValue())) {
            return super.findByToken(token, tokenType);
        }
        return super.findByToken(sha256Hex(token), tokenType);
    }

    private OAuth2Authorization hashTokenValues(OAuth2Authorization authorization) {
        OAuth2Authorization.Builder builder = OAuth2Authorization.from(authorization);

        replaceToken(builder, authorization, OAuth2AuthorizationCode.class,
                (value, original) -> new OAuth2AuthorizationCode(value, original.getIssuedAt(), original.getExpiresAt()));
        replaceToken(builder, authorization, OAuth2AccessToken.class,
                (value, original) -> new OAuth2AccessToken(original.getTokenType(), value,
                        original.getIssuedAt(), original.getExpiresAt(), original.getScopes()));
        replaceToken(builder, authorization, OAuth2RefreshToken.class,
                (value, original) -> new OAuth2RefreshToken(value, original.getIssuedAt(), original.getExpiresAt()));

        return builder.build();
    }

    private <T extends OAuth2Token> void replaceToken(OAuth2Authorization.Builder builder,
                                                       OAuth2Authorization authorization,
                                                       Class<T> tokenClass,
                                                       BiFunction<String, T, T> rebuild) {
        OAuth2Authorization.Token<T> existing = authorization.getToken(tokenClass);
        if (existing == null) {
            return;
        }
        T original = existing.getToken();
        T hashed = rebuild.apply(sha256Hex(original.getTokenValue()), original);
        builder.token(hashed, metadata -> metadata.putAll(existing.getMetadata()));
    }

    private String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
