package com.zxi2233.wechatbridge;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.WindowManager;

import androidx.activity.ComponentActivity;

/**
 * Native, local-only UI for the login bridge.
 *
 * <p>The class stays in Java because the Xposed host runtime references this
 * activity and its historical intent contract. The interface itself is
 * Jetpack Compose.</p>
 */
public final class MainActivity extends ComponentActivity {
    public static final String ACTION_SHOW_CREDENTIAL =
            "com.zxi2233.wechatbridge.action.SHOW_CREDENTIAL";
    public static final String ACTION_AUTHORIZE =
            "com.zxi2233.wechatbridge.action.AUTHORIZE";
    public static final String DEEP_LINK_SCHEME = "jzloginbridge";
    public static final String DEEP_LINK_HOST = "authorize";
    public static final String EXTRA_STATE = "credential_state";
    public static final String EXTRA_SESSION = "credential_session";
    public static final String EXTRA_ORIGIN = "credential_origin";
    public static final String EXTRA_WECHAT_ERROR_CODE = "wechat_error_code";
    public static final String EXTRA_WECHAT_ERROR = "wechat_error";
    public static final String EXTRA_CAPTURED_AT = "captured_at";
    public static final String EXTRA_HOST_VERSION = "host_version";
    public static final String EXTRA_RETURN_URL = "credential_return_url";
    public static final String EXTRA_RETURN_URI = "credential_return_uri";
    public static final String EXTRA_RETURN_PACKAGE = "credential_return_package";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        ComposeBridgeUi.attach(
                this,
                this::openXiaotiancai,
                this::returnToConfiguredCaller,
                () -> setIntent(new Intent(this, MainActivity.class))
        );
        consumeLaunchIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        consumeLaunchIntent(intent);
    }

    private void consumeLaunchIntent(Intent intent) {
        if (intent == null) return;
        if (isDeepLink(intent)) {
            String state = intent.getData() == null
                    ? null : intent.getData().getQueryParameter("state");
            if (!BridgeContract.isValidState(state)) {
                JzLoginBridgeState.INSTANCE.clear();
                return;
            }
            String originalReturnUrl = intent.getData() == null
                    ? "" : intent.getData().getQueryParameter("returnUrl");
            String returnUrl = safeHttpUrl(originalReturnUrl);
            String returnUri = BridgeContract.validateReturnUri(
                    intent.getData().getQueryParameter("returnUri"));
            String returnPackage = BridgeContract.validateReturnPackage(
                    intent.getData().getQueryParameter("returnPackage"));
            JzLoginBridgeState.INSTANCE.markRequest(
                    state, returnUrl, returnUri, returnPackage);
            // Use a local variable because getIntent() is cleared below, while
            // openXiaotiancai() still needs the one-time navigation hints.
            openXiaotiancai(returnUrl, returnUri, returnPackage);
            // Do not retain the one-time state in the activity launch intent.
            setIntent(new Intent(this, MainActivity.class));
            return;
        }
        if (ACTION_SHOW_CREDENTIAL.equals(intent.getAction())
                || intent.hasExtra(EXTRA_SESSION)
                || intent.hasExtra(EXTRA_STATE)) {
            boolean hasSession = !safeExtra(intent, EXTRA_SESSION).isEmpty();
            String origin = safeExtra(intent, EXTRA_ORIGIN);
            JzLoginBridgeState.INSTANCE.applyResult(
                    intent.getAction(),
                    safeExtra(intent, EXTRA_STATE),
                    hasSession,
                    origin,
                    safeExtra(intent, EXTRA_WECHAT_ERROR),
                    intent.getIntExtra(EXTRA_WECHAT_ERROR_CODE, 0),
                    intent.getLongExtra(EXTRA_CAPTURED_AT, 0L),
                    safeExtra(intent, EXTRA_HOST_VERSION)
            );
            String returnUrl = BridgeContract.validateReturnUrl(
                    firstNonEmpty(safeExtra(intent, EXTRA_RETURN_URL),
                            JzLoginBridgeState.INSTANCE.pendingReturnUrl()), origin);
            String returnUri = firstNonEmpty(
                    BridgeContract.validateReturnUri(safeExtra(intent, EXTRA_RETURN_URI)),
                    BridgeContract.validateReturnUri(JzLoginBridgeState.INSTANCE.pendingReturnUri()));
            String returnPackage = firstNonEmpty(
                    BridgeContract.validateReturnPackage(
                            safeExtra(intent, EXTRA_RETURN_PACKAGE)),
                    BridgeContract.validateReturnPackage(
                            JzLoginBridgeState.INSTANCE.pendingReturnPackage()));
            if (hasSession) {
                returnToCaller(returnUri, returnPackage, returnUrl);
            }
            // The Compose UI keeps only a status summary. Remove the raw
            // session and state from the launch intent immediately.
            intent.removeExtra(EXTRA_SESSION);
            intent.removeExtra(EXTRA_STATE);
            intent.removeExtra(EXTRA_RETURN_URL);
            intent.removeExtra(EXTRA_RETURN_URI);
            intent.removeExtra(EXTRA_RETURN_PACKAGE);
            setIntent(new Intent(this, MainActivity.class));
        }
    }

    private boolean isDeepLink(Intent intent) {
        return Intent.ACTION_VIEW.equals(intent.getAction())
                && intent.getData() != null
                && DEEP_LINK_SCHEME.equals(intent.getData().getScheme())
                && DEEP_LINK_HOST.equals(intent.getData().getHost());
    }

    private void returnToCaller(String returnUri, String returnPackage, String returnUrl) {
        if (!returnUri.isEmpty()
                && startReturnIntent(new Intent(Intent.ACTION_VIEW, Uri.parse(returnUri)))) {
            JzLoginBridgeState.INSTANCE.returnRequested();
            return;
        }
        if (!returnPackage.isEmpty()) {
            Intent packageIntent = getPackageManager().getLaunchIntentForPackage(returnPackage);
            if (packageIntent != null && startReturnIntent(packageIntent)) {
                JzLoginBridgeState.INSTANCE.returnRequested();
                return;
            }
        }
        if (!returnUrl.isEmpty()) {
            Intent browser = new Intent(Intent.ACTION_VIEW, Uri.parse(returnUrl));
            browser.addCategory(Intent.CATEGORY_BROWSABLE);
            if (startReturnIntent(browser)) {
                JzLoginBridgeState.INSTANCE.returnRequested();
                return;
            }
        }
        // The success screen remains visible and its retry action is still available.
        JzLoginBridgeState.INSTANCE.reportReturnUnavailable();
    }

    private boolean startReturnIntent(Intent intent) {
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(intent);
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private void returnToConfiguredCaller() {
        String origin = JzLoginBridgeState.INSTANCE.getResult() == null
                ? "" : JzLoginBridgeState.INSTANCE.getResult().getOrigin();
        String returnUrl = BridgeContract.validateReturnUrl(
                JzLoginBridgeState.INSTANCE.pendingReturnUrl(), origin);
        String returnUri = BridgeContract.validateReturnUri(
                JzLoginBridgeState.INSTANCE.pendingReturnUri());
        String returnPackage = BridgeContract.validateReturnPackage(
                JzLoginBridgeState.INSTANCE.pendingReturnPackage());
        returnToCaller(returnUri, returnPackage, returnUrl);
    }

    private static String safeHttpUrl(String value) {
        if (value == null || value.length() > 2048) return "";
        try {
            Uri uri = Uri.parse(value);
            String scheme = uri.getScheme();
            if (!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme)) return "";
            return uri.getHost() == null ? "" : uri.toString();
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private void openXiaotiancai() {
        openXiaotiancai(
                JzLoginBridgeState.INSTANCE.pendingReturnUrl(),
                JzLoginBridgeState.INSTANCE.pendingReturnUri(),
                JzLoginBridgeState.INSTANCE.pendingReturnPackage());
    }

    private void openXiaotiancai(
            String returnUrl, String returnUri, String returnPackage) {
        final Intent launch = getPackageManager().getLaunchIntentForPackage(
                BridgeContract.HOST_PACKAGE);
        if (launch == null) {
            JzLoginBridgeState.INSTANCE.reportHostUnavailable();
            return;
        }
        String state = JzLoginBridgeState.INSTANCE.pendingRequest();
        if (BridgeContract.isValidState(state)) {
            String origin = getIntent().getData() == null
                    ? null : getIntent().getData().getQueryParameter("origin");
            launch.setAction(BridgeContract.HOST_AUTH_ACTION);
            launch.putExtra(BridgeContract.EXTRA_DEEP_LINK_STATE, state);
            if (!returnUrl.isEmpty()) {
                launch.putExtra(BridgeContract.EXTRA_DEEP_LINK_RETURN_URL, returnUrl);
            }
            if (!returnUri.isEmpty()) {
                launch.putExtra(BridgeContract.EXTRA_DEEP_LINK_RETURN_URI, returnUri);
            }
            if (!returnPackage.isEmpty()) {
                launch.putExtra(
                        BridgeContract.EXTRA_DEEP_LINK_RETURN_PACKAGE, returnPackage);
            }
            if (origin != null && !origin.isEmpty()) {
                launch.putExtra(BridgeContract.EXTRA_DEEP_LINK_ORIGIN, origin);
            }
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        try {
            startActivity(launch);
            JzLoginBridgeState.INSTANCE.markHostOpening();
        } catch (RuntimeException error) {
            JzLoginBridgeState.INSTANCE.reportHostUnavailable();
        }
    }

    private static String firstNonEmpty(String... values) {
        if (values == null) return "";
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) return value.trim();
        }
        return "";
    }

    private static String safeExtra(Intent intent, String name) {
        String value = intent.getStringExtra(name);
        return value == null ? "" : value;
    }

}

