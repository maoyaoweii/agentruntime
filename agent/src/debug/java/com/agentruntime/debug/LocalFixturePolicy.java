package com.agentruntime.debug;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.net.URI;
import java.net.URISyntaxException;

/** Debug fixture only. No host, URL, selector, action, or coordinates are caller-controlled. */
public final class LocalFixturePolicy {
    public static final String URL = "https://synthetic.invalid/fixture";
    public static final String MARKER = "CUE_LOCAL_SYNTHETIC_FIXTURE_V1";
    public static final String LINK = "Allowed local link";
    public static final long TTL_MS = 120_000;
    private String token;
    private long expires;
    private boolean clicked;
    private final java.util.Set<String> fixedActions = new java.util.HashSet<>();

    public static void authorize(boolean debug, int uid, int sdk, String abi, String hardware) {
        if (!debug || uid != 2000 || sdk != 30 || !"x86_64".equals(abi)
                || !("ranchu".equals(hardware) || "goldfish".equals(hardware))) {
            throw new SecurityException("LOCAL_AVD_SHELL_ONLY");
        }
    }
    public static boolean allowedUrl(String url) {
        if (url == null) return false;
        try {
            URI u = new URI(url); // No normalization, decoding, default ports, or prefix matching.
            return u.isAbsolute() && !u.isOpaque()
                    && "https".equals(u.getScheme())
                    && "synthetic.invalid".equals(u.getHost())
                    && "synthetic.invalid".equals(u.getRawAuthority())
                    && u.getRawUserInfo() == null && u.getPort() == -1
                    && "/fixture".equals(u.getRawPath()) && u.getRawQuery() == null
                    && (u.getRawFragment() == null || "allowed".equals(u.getRawFragment()))
                    && (URL.equals(url) || (URL + "#allowed").equals(url));
        } catch (URISyntaxException e) { return false; }
    }
    /** Fixed classifications only; never echo an unexpected URL, query, or user info to logs. */
    static String urlDiagnostic(String url) {
        if (url == null) return "NO_URL";
        if (URL.equals(url)) return "EXACT_BASE";
        if ((URL + "#allowed").equals(url)) return "EXACT_ALLOWED_FRAGMENT";
        if ("about:blank".equals(url)) return "ABOUT_BLANK_REJECTED";
        return "UNEXPECTED_URL_REDACTED";
    }
    public static void validateRequest(String method, String arg, boolean hasExtras) {
        if (hasExtras || !("open".equals(method) || "read".equals(method)
                || "click".equals(method) || "close".equals(method)
                || LocalFixtureControl.isAction(method) || "capture".equals(method)))
            throw new SecurityException("REQUEST_NOT_ALLOWED");
        if ("open".equals(method) ? arg != null : arg == null || !arg.matches("[0-9a-f]{64}"))
            throw new SecurityException("INVALID_ARGUMENT");
    }
    public String open(long now) {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        StringBuilder s = new StringBuilder();
        for (byte b : bytes) s.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        token = s.toString();
        expires = now + TTL_MS;
        clicked = false;
        fixedActions.clear();
        return token;
    }
    public void require(String candidate, long now) {
        if (token == null || now >= expires || candidate == null
                || !MessageDigest.isEqual(token.getBytes(StandardCharsets.US_ASCII),
                    candidate.getBytes(StandardCharsets.US_ASCII)))
            throw new SecurityException("SESSION_INVALID_OR_EXPIRED");
    }
    public void consumeClick(String candidate, long now) {
        require(candidate, now);
        if (clicked) throw new SecurityException("CLICK_ALREADY_CONSUMED");
        clicked = true; // Consume even if accessibility dispatch fails; never retry implicitly.
    }
    public void consumeFixed(String candidate, long now, String action) {
        require(candidate, now);
        if (!(LocalFixtureControl.isAction(action) || "capture".equals(action)))
            throw new SecurityException("REQUEST_NOT_ALLOWED");
        if (!fixedActions.add(action)) throw new SecurityException("FIXED_ACTION_ALREADY_CONSUMED");
    }
    boolean consumed(String action) { return fixedActions.contains(action); } // Main-thread only.
    public void close() { token = null; expires = 0; }
}
