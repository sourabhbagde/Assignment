package com.example.urlshortener.adapter.out.security;

import com.example.urlshortener.application.config.AppProperties;
import com.example.urlshortener.application.port.out.UrlSafetyInspector;
import com.example.urlshortener.adapter.out.codec.UrlNormalizer;
import com.example.urlshortener.domain.exception.InvalidUrlException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;

/**
 * The destination-URL security boundary. A URL shortener that will shorten
 * <em>anything</em> is an SSRF and open-redirect amplifier, so every create goes
 * through here.
 *
 * <p>Checks, in order:
 * <ol>
 *   <li>non-empty, within the size budget, no control/whitespace characters
 *       (Location-header injection);</li>
 *   <li>parses as an absolute hierarchical URL with an authority;</li>
 *   <li>scheme is {@code http}/{@code https} (blocks {@code javascript:},
 *       {@code data:}, {@code file:}, {@code ftp:}, …);</li>
 *   <li>no embedded credentials ({@code user:pass@host} — phishing / secret leak);</li>
 *   <li>host is a syntactically valid hostname or IP literal;</li>
 *   <li>host is not an internal name ({@code localhost}, {@code *.local},
 *       {@code *.internal}, cloud-metadata names) and not this service itself;</li>
 *   <li>if the host is an IP literal — <b>including decimal / hex / octal / short
 *       forms and IPv4-mapped IPv6</b> — it is not loopback / private / link-local
 *       / CGNAT / multicast / reserved / unspecified;</li>
 *   <li>optionally ({@code app.validate-dns=true}) the host is resolved and every
 *       returned address is re-checked (DNS-rebinding mitigation).</li>
 * </ol>
 * On success the URL is normalised and SHA-256 hashed for dedupe.
 *
 * <p>Residual risk: we never fetch the URL server-side, so time-of-check/
 * time-of-use rebinding only affects the client's own browser, not this service.
 */
@Component
public class SsrfAwareUrlSafetyInspector implements UrlSafetyInspector {

    private static final Logger log = LoggerFactory.getLogger(SsrfAwareUrlSafetyInspector.class);

    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");
    private static final Set<String> BLOCKED_HOSTNAMES = Set.of(
            "localhost", "localhost.localdomain", "ip6-localhost", "ip6-loopback",
            "metadata", "metadata.google.internal", "instance-data");
    private static final Set<String> BLOCKED_SUFFIXES = Set.of(
            ".local", ".localdomain", ".internal", ".lan", ".intranet", ".home.arpa");
    /** RFC 1123 hostname (labels 1-63 chars, alnum + hyphen, no leading/trailing hyphen). */
    private static final java.util.regex.Pattern HOSTNAME =
            java.util.regex.Pattern.compile(
                    "^(?=.{1,253}$)(?!-)[A-Za-z0-9-]{1,63}(?<!-)(\\.(?!-)[A-Za-z0-9-]{1,63}(?<!-))*\\.?$");

    private final AppProperties props;
    private final String ownHost;

    public SsrfAwareUrlSafetyInspector(AppProperties props) {
        this.props = props;
        this.ownHost = hostOf(props.baseUrl());
    }

    @Override
    public Result inspect(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new InvalidUrlException("url is required");
        }
        String candidate = rawUrl.strip();
        if (candidate.length() > props.maxUrlLength()) {
            throw new InvalidUrlException("url exceeds " + props.maxUrlLength() + " characters");
        }
        if (containsControlOrSpace(candidate)) {
            throw new InvalidUrlException("url contains illegal control or whitespace characters");
        }

        final URI uri;
        try {
            uri = new URI(candidate);
        } catch (URISyntaxException e) {
            throw new InvalidUrlException("url is not syntactically valid");
        }
        if (!uri.isAbsolute() || uri.isOpaque() || uri.getRawAuthority() == null) {
            throw new InvalidUrlException("url must be an absolute URL including an http(s) scheme and host");
        }

        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        if (!ALLOWED_SCHEMES.contains(scheme)) {
            throw new InvalidUrlException("unsupported scheme \"" + scheme + "\" (only http and https are allowed)");
        }
        if (uri.getRawUserInfo() != null || uri.getRawAuthority().indexOf('@') >= 0) {
            throw new InvalidUrlException("url must not contain embedded credentials");
        }

        String host = stripBrackets(extractHost(uri).toLowerCase(Locale.ROOT));
        if (host.isEmpty()) {
            throw new InvalidUrlException("url must include a host");
        }

        InetAddress ipLiteral = parseIpLiteral(host);
        if (ipLiteral == null && !HOSTNAME.matcher(host).matches()) {
            throw new InvalidUrlException("url host is not a valid hostname or IP address");
        }

        if (props.blockPrivateAddresses()) {
            enforceHostPolicy(host, ipLiteral);
        }

        String normalized = UrlNormalizer.normalize(uri, host);
        if (normalized.length() > props.maxUrlLength()) {
            throw new InvalidUrlException("normalised url exceeds " + props.maxUrlLength() + " characters");
        }
        return new Result(normalized, sha256Hex(normalized));
    }

    // --------------------------------------------------------------- host policy

    private void enforceHostPolicy(String host, InetAddress ipLiteral) {
        if (ownHost != null && host.equals(ownHost)) {
            throw new InvalidUrlException("destination must not point back at this service");
        }
        if (BLOCKED_HOSTNAMES.contains(host)) {
            throw new InvalidUrlException("destination host is not allowed");
        }
        for (String suffix : BLOCKED_SUFFIXES) {
            if (host.endsWith(suffix)) {
                throw new InvalidUrlException("destination host is not allowed");
            }
        }

        if (ipLiteral != null) {
            if (isDisallowedAddress(ipLiteral)) {
                throw new InvalidUrlException(
                        "destination resolves to a loopback, private, link-local or reserved address");
            }
            return; // a safe public IP literal — no DNS to do
        }

        if (props.validateDns()) {
            InetAddress[] resolved;
            try {
                resolved = InetAddress.getAllByName(host);
            } catch (UnknownHostException e) {
                throw new InvalidUrlException("destination hostname could not be resolved");
            }
            for (InetAddress addr : resolved) {
                if (isDisallowedAddress(addr)) {
                    throw new InvalidUrlException(
                            "destination hostname resolves to a private or reserved address");
                }
            }
        }
    }

    /**
     * Interpret {@code host} as an IP literal <b>without any DNS</b>. Handles the
     * obfuscated IPv4 forms (single decimal like {@code 2130706433}, hex
     * {@code 0x7f000001}, octal {@code 0177.0.0.1}, 1–3 part short forms) that a
     * naive dotted-quad check misses, plus plain/de-bracketed IPv6.
     *
     * @return the address, or {@code null} if {@code host} is not an IP literal
     */
    static InetAddress parseIpLiteral(String host) {
        if (host.indexOf(':') >= 0) {
            try {
                return InetAddress.getByName("[" + host + "]"); // no DNS for IPv6 literals
            } catch (UnknownHostException e) {
                return null;
            }
        }
        Long packed = packLooseIpv4(host);
        if (packed == null) {
            return null;
        }
        byte[] octets = {
                (byte) (packed >>> 24), (byte) (packed >>> 16), (byte) (packed >>> 8), (byte) (packed & 0xFF)
        };
        try {
            return InetAddress.getByAddress(octets);
        } catch (UnknownHostException e) {
            return null; // unreachable for a 4-byte array
        }
    }

    /** inet_aton-style: 1..4 parts, each decimal / octal (0-prefixed) / hex (0x). */
    static Long packLooseIpv4(String host) {
        if (host.isEmpty() || host.endsWith(".")) {
            return null;
        }
        String[] parts = host.split("\\.", -1);
        if (parts.length < 1 || parts.length > 4) {
            return null;
        }
        long[] values = new long[parts.length];
        for (int i = 0; i < parts.length; i++) {
            Long v = parsePart(parts[i]);
            if (v == null) {
                return null;
            }
            values[i] = v;
        }
        long result;
        switch (parts.length) {
            case 1 -> result = values[0];
            case 2 -> {
                if (values[0] > 0xFF || values[1] > 0xFFFFFF) {
                    return null;
                }
                result = (values[0] << 24) | values[1];
            }
            case 3 -> {
                if (values[0] > 0xFF || values[1] > 0xFF || values[2] > 0xFFFF) {
                    return null;
                }
                result = (values[0] << 24) | (values[1] << 16) | values[2];
            }
            default -> {
                for (long v : values) {
                    if (v > 0xFF) {
                        return null;
                    }
                }
                result = (values[0] << 24) | (values[1] << 16) | (values[2] << 8) | values[3];
            }
        }
        return (result < 0 || result > 0xFFFFFFFFL) ? null : result;
    }

    private static Long parsePart(String part) {
        if (part.isEmpty()) {
            return null;
        }
        String lower = part.toLowerCase(Locale.ROOT);
        final int radix;
        final String digits;
        if (lower.startsWith("0x")) {
            radix = 16;
            digits = lower.substring(2);
        } else if (lower.length() > 1 && lower.charAt(0) == '0') {
            radix = 8;
            digits = lower.substring(1);
        } else {
            radix = 10;
            digits = lower;
        }
        if (digits.isEmpty()) {
            return null;
        }
        try {
            BigInteger value = new BigInteger(digits, radix);
            return (value.signum() < 0 || value.bitLength() > 32) ? null : value.longValue();
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** True if the address must never be a shortener destination. */
    static boolean isDisallowedAddress(InetAddress addr) {
        if (addr.isAnyLocalAddress()          // 0.0.0.0 / ::
                || addr.isLoopbackAddress()   // 127/8, ::1
                || addr.isLinkLocalAddress()  // 169.254/16 (incl. cloud metadata), fe80::/10
                || addr.isSiteLocalAddress()  // 10/8, 172.16/12, 192.168/16
                || addr.isMulticastAddress()) {
            return true;
        }
        byte[] b = addr.getAddress();
        if (b.length == 4) {
            int first = b[0] & 0xFF;
            int second = b[1] & 0xFF;
            if (first == 0) {
                return true;                                   // 0/8 "this network"
            }
            if (first == 100 && second >= 64 && second <= 127) {
                return true;                                   // 100.64/10 CGNAT
            }
            if (first == 169 && second == 254) {
                return true;                                   // link-local (belt-and-braces)
            }
            if (first == 192 && second == 0 && (b[2] & 0xFF) == 2) {
                return true;                                   // 192.0.2/24 TEST-NET-1
            }
            if (first == 198 && (b[1] & 0xFE) == 18) {
                return true;                                   // 198.18/15 benchmarking
            }
            return first >= 240;                               // 240/4 reserved + 255.255.255.255
        }
        if (b.length == 16) {
            if ((b[0] & 0xFE) == 0xFC) {
                return true;                                   // fc00::/7 unique-local
            }
            boolean mappedPrefix = true;
            for (int i = 0; i < 10; i++) {
                if (b[i] != 0) {
                    mappedPrefix = false;
                    break;
                }
            }
            if (mappedPrefix && (b[10] & 0xFF) == 0xFF && (b[11] & 0xFF) == 0xFF) {
                try {
                    return isDisallowedAddress(InetAddress.getByAddress(
                            new byte[] {b[12], b[13], b[14], b[15]}));
                } catch (UnknownHostException ignored) {
                    return true;
                }
            }
        }
        return false;
    }

    // --------------------------------------------------------------- utilities

    private static String extractHost(URI uri) {
        if (uri.getHost() != null) {
            return uri.getHost();
        }
        // Registry-based authority (obfuscated IP, unusual host): strip userinfo and port ourselves.
        String auth = uri.getRawAuthority();
        int at = auth.lastIndexOf('@');
        if (at >= 0) {
            auth = auth.substring(at + 1);
        }
        if (auth.startsWith("[")) {
            int close = auth.indexOf(']');
            return close > 0 ? auth.substring(0, close + 1) : auth;
        }
        int colon = auth.indexOf(':');
        return colon >= 0 ? auth.substring(0, colon) : auth;
    }

    private static boolean containsControlOrSpace(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c <= 0x20 || c == 0x7F || Character.isWhitespace(c)) {
                return true;
            }
        }
        return false;
    }

    private static String stripBrackets(String host) {
        return (host.startsWith("[") && host.endsWith("]")) ? host.substring(1, host.length() - 1) : host;
    }

    private static String hostOf(String url) {
        try {
            String h = new URI(url).getHost();
            return h == null ? null : h.toLowerCase(Locale.ROOT);
        } catch (URISyntaxException e) {
            log.warn("app.base-url is not a valid URI; self-redirect guard disabled");
            return null;
        }
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(UrlNormalizer.utf8(input)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
