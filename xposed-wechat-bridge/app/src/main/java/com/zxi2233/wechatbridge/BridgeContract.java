package com.zxi2233.wechatbridge;

import java.net.URI;
import java.util.Locale;
import java.util.regex.Pattern;

/** Wire constants and input validation shared by the HTTP and Xposed sides. */
public final class BridgeContract {
    public static final String HOST_PACKAGE = "com.xtc.watch";
    public static final String MODULE_PACKAGE = "com.zxi2233.wechatbridge";
    public static final int PORT = 38787;
    public static final long REQUEST_TTL_MS = 120_000L;
    public static final String STATE_PREFIX = "jzwb1.";
    public static final String EXTRA_DEEP_LINK_STATE = "jz_login_bridge_state";
    public static final String EXTRA_DEEP_LINK_ORIGIN = "jz_login_bridge_origin";
    public static final String EXTRA_DEEP_LINK_RETURN_URL =
            "jz_login_bridge_return_url";
    public static final String EXTRA_DEEP_LINK_RETURN_URI =
            "jz_login_bridge_return_uri";
    public static final String EXTRA_DEEP_LINK_RETURN_PACKAGE =
            "jz_login_bridge_return_package";
    public static final String HOST_AUTH_ACTION =
            HOST_PACKAGE + ".account.WECHAT_LOGIN";

    private static final Pattern STATE = Pattern.compile("^[A-Za-z0-9_-]{16,128}$");

    private BridgeContract() {
    }

    public static boolean isValidState(String state) {
        return state != null && STATE.matcher(state).matches();
    }

    public static String wireState(String state) {
        return STATE_PREFIX + state;
    }

    public static boolean shouldIntercept(String state) {
        return state != null && (state.startsWith(STATE_PREFIX) || "login_state".equals(state));
    }

    /** Confirms only real hosts for the JZ HTTP client, not arbitrary CORS origins. */
    public static String validateHost(String value) {
        if (value == null) return null;
        String host = value.trim();
        if (host.isEmpty() || host.length() > 255) return null;
        if (host.contains("/") || host.contains("@")) return null;
        return host;
    }

    /**
     * The browser's return URL is only a navigation hint, but still restrict it
     * to a real HTTP(S) page and the origin that owns this login request.
     */
    public static String validateReturnUrl(String value, String origin) {
        if (value == null || value.isEmpty() || value.length() > 2048
                || origin == null || origin.isEmpty()) return "";
        try {
            String normalizedOrigin = normalizeOrigin(origin);
            URI uri = URI.create(value);
            String scheme = uri.getScheme();
            if (scheme == null || uri.getHost() == null
                    || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
                return "";
            }
            int port = uri.getPort();
            if (port < 0) port = "https".equalsIgnoreCase(scheme) ? 443 : 80;
            String pageOrigin = scheme.toLowerCase(Locale.ROOT) + "://"
                    + uri.getHost().toLowerCase(Locale.ROOT)
                    + (((scheme.equalsIgnoreCase("http") && port == 80)
                    || (scheme.equalsIgnoreCase("https") && port == 443))
                    ? "" : ":" + port);
            return normalizedOrigin.equals(pageOrigin) ? value : "";
        } catch (IllegalArgumentException | BridgeException error) {
            return "";
        }
    }

    /**
     * Native callers can provide their own non-network return URI. Dangerous
     * or browser-owned schemes stay blocked so a webpage cannot use the bridge
     * as a generic external-intent launcher.
     */
    public static String validateReturnUri(String value) {
        if (value == null || value.isEmpty() || value.length() > 2048) return "";
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme();
            if (scheme == null || !scheme.matches("[A-Za-z][A-Za-z0-9+.-]{1,31}")
                    || uri.getRawUserInfo() != null) {
                return "";
            }
            switch (scheme.toLowerCase(Locale.ROOT)) {
                case "http":
                case "https":
                case "javascript":
                case "file":
                case "content":
                case "data":
                case "blob":
                case "intent":
                case "android-app":
                case "market":
                case "jzloginbridge":
                    return "";
                default:
                    return value;
            }
        } catch (IllegalArgumentException error) {
            return "";
        }
    }

    /** Only syntactically valid Android package names can be used as targets. */
    public static String validateReturnPackage(String value) {
        if (value == null || value.length() > 200
                || !value.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")) {
            return "";
        }
        return MODULE_PACKAGE.equals(value) ? "" : value;
    }

    public static boolean sameWechatResponse(String expected, String received) {
        return expected == null ? received == null : expected.equals(received);
    }

    /**
     * Only a real HTTP(S) browser origin is accepted. In particular, "null",
     * custom schemes, credentials and paths cannot be used as callers.
     */
    public static String normalizeOrigin(String value) throws BridgeException {
        if (value == null || value.length() == 0 || value.length() > 255) {
            throw new BridgeException(403, "origin_required", "需要有效的网页 Origin。");
        }
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || host == null || uri.getRawUserInfo() != null
                    || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || (uri.getRawPath() != null && !uri.getRawPath().isEmpty())) {
                throw new IllegalArgumentException("not an origin");
            }
            scheme = scheme.toLowerCase(Locale.ROOT);
            if (!"http".equals(scheme) && !"https".equals(scheme)) {
                throw new IllegalArgumentException("unsupported scheme");
            }
            int port = uri.getPort();
            if (port < 0) {
                port = "https".equals(scheme) ? 443 : 80;
            }
            if (port < 1 || port > 65535) {
                throw new IllegalArgumentException("invalid port");
            }
            host = host.toLowerCase(Locale.ROOT);
            if (host.contains(":") && !host.startsWith("[")) {
                host = "[" + host + "]";
            }
            boolean defaultPort = ("http".equals(scheme) && port == 80)
                    || ("https".equals(scheme) && port == 443);
            return scheme + "://" + host + (defaultPort ? "" : ":" + port);
        } catch (IllegalArgumentException error) {
            throw new BridgeException(403, "invalid_origin", "网页 Origin 不受支持。");
        }
    }
}

