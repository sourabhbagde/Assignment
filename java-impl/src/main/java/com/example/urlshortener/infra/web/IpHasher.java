package com.example.urlshortener.infra.web;

import com.example.urlshortener.application.config.AppProperties;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * One-way, salted hash of a client IP. The raw IP is never persisted or logged;
 * only this digest is stored with a click event, which is enough for
 * abuse/uniqueness signals without holding PII at rest.
 *
 * <p>The salt is per-deployment ({@code app.ip-hash-salt}); rotating it simply
 * makes historical hashes un-joinable to new ones, which is an acceptable
 * privacy posture.
 */
@Component
public class IpHasher {

    private final byte[] salt;

    public IpHasher(AppProperties props) {
        this.salt = props.ipHashSalt().getBytes(StandardCharsets.UTF_8);
    }

    public String hash(String ip) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(salt);
            md.update((byte) ':');
            md.update(ip.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
