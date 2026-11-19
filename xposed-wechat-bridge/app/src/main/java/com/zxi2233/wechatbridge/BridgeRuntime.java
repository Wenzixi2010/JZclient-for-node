package com.zxi2233.wechatbridge;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.AndroidAppHelper;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.lang.reflect.Proxy;
import java.lang.ref.WeakReference;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Coordinates user confirmation, the host WeChat SDK and the local server. */
final class BridgeRuntime implements LoopbackHttpServer.Listener {
    private static final String TAG = "[JZWechatBridge] ";

    private final ClassLoader hostClassLoader;
    private final RequestRegistry registry = new RequestRegistry();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private WeakReference<Activity> resumedActivity = new WeakReference<>(null);
    private AlertDialog confirmationDialog;
    private String confirmationState;
    private boolean retryScheduled;
    private long lastHostLaunch;
    private String lastPreviewState = "";
    private final AtomicBoolean hostLoginInFlight = new AtomicBoolean(false);
    private volatile String hostSessionJson;

    BridgeRuntime(ClassLoader hostClassLoader) {
        this.hostClassLoader = hostClassLoader;
    }

    void install() throws Throwable {
        hookWechatCallback();
        hookOfficialSessionStore();
        hookActivities();
        LoopbackHttpServer server = new LoopbackHttpServer(registry, this);
        server.start();
    }

    private void hookActivities() {
        XposedHelpers.findAndHookMethod(Activity.class, "onResume", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                resumedActivity = new WeakReference<>((Activity) param.thisObject);
                showNextConfirmation();
            }
        });
        XposedHelpers.findAndHookMethod(Activity.class, "onPause", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Activity current = resumedActivity.get();
                if (current == param.thisObject) resumedActivity.clear();
            }
        });
    }

    private void hookOfficialSessionStore() {
        try {
            Class<?> storeClass = Class.forName(
                    "com.xtc.common.shared.ShareToolManger", false, hostClassLoader);
            XposedHelpers.findAndHookMethod(storeClass, "saveString",
                    String.class, String.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!"mobile_account_login_status".equals(param.args[0])
                                    || param.args[1] == null) return;
                            try {
                                JSONObject record = new JSONObject(
                                        String.valueOf(param.args[1]));
                                JSONObject account = record.optJSONObject("MobileAccount");
                                if (account == null) return;
                                String token = account.optString("token", "");
                                if (token.isEmpty()) return;
                                hostSessionJson = new JSONObject()
                                        .put("accountId",
                                                account.optString("mobileId", ""))
                                        .put("authId", account.optString("authId", ""))
                                        .put("mobileId", account.optString("mobileId", ""))
                                        .put("ticket", token)
                                        .put("userName", firstNonEmpty(
                                                account.optString("name", ""),
                                                account.optString("number", ""),
                                                account.optString("mobileId", "")))
                                        .put("mobileNumber",
                                                account.optString("number", ""))
                                        .put("avatar", account.optString("icon", ""))
                                        .put("ssoAccountId", ticketAccountId(token))
                                        .toString();
                                XposedBridge.log(TAG + " captured host account session");
                            } catch (Throwable error) {
                                XposedBridge.log(TAG + " host session parse skipped");
                            }
                        }
                    });
        } catch (Throwable error) {
            XposedBridge.log(TAG + " session store hook unavailable: " + error);
        }
    }

    private void hookWechatCallback() throws Throwable {
        final Class<?> authResponseClass = Class.forName(
                "com.tencent.mm.opensdk.modelmsg.SendAuth$Resp", false, hostClassLoader);
        XposedHelpers.findAndHookMethod(
                "com.xtc.common.util.BaseWXEntryActivity",
                hostClassLoader,
                "onResp",
                "com.tencent.mm.opensdk.modelbase.BaseResp",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        Object response = param.args[0];
                        if (response == null || !authResponseClass.isInstance(response)) return;
                        // XTC's native login starts SendAuth.Req with this value
                        // but Android's official SendAuth.Resp leaves state null
                        // in that flow. Treat a null state as the native login
                        // response unless a bridge request matches its state.
                        String wireState = (String) XposedHelpers.getObjectField(response, "state");
                        boolean nativeLogin = wireState == null
                                || "login_state".equals(wireState);
                        boolean bridgeRequest = !nativeLogin && registry.matches(wireState);
                        if (!nativeLogin && !bridgeRequest) return;

                        int errorCode = XposedHelpers.getIntField(response, "errCode");
                        String error = (String) XposedHelpers.getObjectField(response, "errStr");
                        String code = (String) XposedHelpers.getObjectField(response, "code");
                        long now = System.currentTimeMillis();
                        AuthorizationSnapshot preview;
                        if (nativeLogin) {
                            preview = hostLoginSnapshot("login_state", code, errorCode, error, now);
                        } else {
                            boolean owned = registry.claimWechat(
                                    wireState, errorCode, error, now);
                            if (!owned) return;
                            preview = registry.peek(
                                    wireState.substring(BridgeContract.STATE_PREFIX.length()), now);
                        }

                        // Never let BaseWXEntryActivity consume this callback.
                        // Web requests are completed by calling the host's own
                        // official login implementation below.
                        param.setResult(null);
                        if (nativeLogin) {
                            showCredentialPreview(preview);
                        } else if (errorCode == 0) {
                            beginHostLogin(wireState, code);
                        } else {
                            showCredentialPreview(preview);
                        }
                        if (param.thisObject instanceof Activity) {
                            ((Activity) param.thisObject).finish();
                        }
                        XposedBridge.log(TAG + "intercepted "
                                + (nativeLogin ? "host login" : "web authorization")
                                + ", errCode=" + errorCode);
                    }
                });
    }

    private AuthorizationSnapshot hostLoginSnapshot(String state, String code,
                                                     int errorCode, String error, long now) {
        boolean authorized = errorCode == 0 && code != null && !code.isEmpty();
        return new AuthorizationSnapshot(
                state,
                authorized ? AuthorizationSnapshot.AUTHORIZED : AuthorizationSnapshot.ERROR,
                now + BridgeContract.REQUEST_TTL_MS,
                authorized ? "native_login_intercepted" : null,
                errorCode,
                error,
                authorized ? null : (errorCode == -2 ? "wechat_cancelled" : "wechat_error"),
                authorized ? null : (errorCode == -2
                        ? "你取消了微信授权。"
                        : (error == null || error.isEmpty() ? "微信授权失败。" : error)));
    }

    private void beginHostLogin(final String wireState, final String code) {
        if (code == null || code.isEmpty()) {
            registry.failLogin(wireState, "missing_wechat_code",
                    "微信没有返回授权码，请重新授权。", System.currentTimeMillis());
            return;
        }
        if (!hostLoginInFlight.compareAndSet(false, true)) {
            registry.failLogin(wireState, "login_busy",
                    "小天才正在完成另一个登录，请稍后重试。", System.currentTimeMillis());
            return;
        }
        mainHandler.post(() -> {
            try {
                Context context = AndroidAppHelper.currentApplication();
                if (context == null) throw new IllegalStateException("host context is null");
                Class<?> listenerClass = Class.forName(
                        "com.xtc.component.api.account.callback.OnLoginListener",
                        false, hostClassLoader);
                Class<?> mobileAccountClass = Class.forName(
                        "com.xtc.component.api.account.bean.MobileAccount",
                        false, hostClassLoader);
                Object service = XposedHelpers.callStaticMethod(
                        Class.forName("com.xtc.account.service.impl.Gambia",
                                false, hostClassLoader), "Yd", context);
                Object listener = Proxy.newProxyInstance(hostClassLoader,
                        new Class<?>[]{listenerClass}, (proxy, method, args) -> {
                            String name = method.getName();
                            if ("onSuccess".equals(name)) {
                                mainHandler.postDelayed(
                                        () -> completeHostLogin(
                                                wireState, service, mobileAccountClass), 150L);
                                return null;
                            }
                            if ("onFailed".equals(name)) {
                                Object failure = args != null && args.length > 0 ? args[0] : null;
                                String message = failureMessage(failure);
                                registry.failLogin(wireState, "host_login_failed",
                                        message.isEmpty() ? "小天才未能完成微信登录。"
                                                : "小天才未能完成微信登录：" + message,
                                        System.currentTimeMillis());
                                mainHandler.post(this::releaseHostLogin);
                                return null;
                            }
                            if ("hashCode".equals(name)) return System.identityHashCode(proxy);
                            if ("equals".equals(name)) return proxy == args[0];
                            if ("toString".equals(name)) return "JZWechatLoginListener";
                            return null;
                        });
                XposedHelpers.callMethod(service, "wc", null, code, "WECHET", listener);
                XposedBridge.log(TAG + " delegated WeChat code to host login");
            } catch (Throwable error) {
                XposedBridge.log(TAG + " host login failed: " + error);
                registry.failLogin(wireState, "host_incompatible",
                        "当前小天才版本无法完成微信登录，请查看 LSPosed 日志。",
                        System.currentTimeMillis());
                releaseHostLogin();
            }
        });
    }

    private void completeHostLogin(final String wireState, final Object service,
                                   final Class<?> mobileAccountClass) {
        try {
            Object account = loadMobileAccount(service, mobileAccountClass);
            String sessionJson = hostSessionJson;
            if (account != null && mobileAccountClass.isInstance(account)) {
                String mobileId = stringField(account, "getMobileId");
                String token = stringField(account, "getToken");
                String authId = stringField(account, "getAuthId");
                if (!token.isEmpty()) {
                    sessionJson = new JSONObject()
                            .put("accountId", mobileId)
                            .put("authId", authId)
                            .put("mobileId", mobileId)
                            .put("ticket", token)
                            .put("userName", firstNonEmpty(
                                    stringField(account, "getName"),
                                    stringField(account, "getNumber"),
                                    mobileId))
                            .put("mobileNumber", firstNonEmpty(
                                    stringField(account, "getNumber"),
                                    stringField(account, "getContactNumber")))
                            .put("avatar", stringField(account, "getIcon"))
                            .put("ssoAccountId", ticketAccountId(token))
                            .toString();
                }
            }
            if (sessionJson == null || sessionJson.isEmpty()) {
                throw new IllegalStateException("host session is unavailable");
            }
            boolean completed = registry.completeWithAccount(
                    wireState, sessionJson, 0, null, System.currentTimeMillis());
            if (completed) {
                XposedBridge.log(TAG + " host account login completed");
                String state = wireState.substring(BridgeContract.STATE_PREFIX.length());
                AuthorizationSnapshot request = registry.peek(
                        state, System.currentTimeMillis());
                if (request != null) showCredentialPreview(request);
            }
        } catch (Throwable error) {
            XposedBridge.log(TAG + " reading host account failed: " + error);
            registry.failLogin(wireState, "session_unavailable",
                    "小天才登录成功，但无法读取账号会话。",
                    System.currentTimeMillis());
        } finally {
            mainHandler.post(this::releaseHostLogin);
        }
    }

    private void releaseHostLogin() {
        hostLoginInFlight.set(false);
    }

    private Object loadMobileAccount(Object service, Class<?> mobileAccountClass) {
        Object account = XposedHelpers.callMethod(service, "Xb");
        if (account != null && mobileAccountClass.isInstance(account)) return account;
        Context context = AndroidAppHelper.currentApplication();
        if (context == null) return null;
        try {
            Class<?> api = Class.forName(
                    "com.xtc.component.api.account.AccountInfoApi",
                    false, hostClassLoader);
            account = XposedHelpers.callStaticMethod(api, "getMobileAccount", context);
            return account != null && mobileAccountClass.isInstance(account) ? account : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String stringField(Object target, String getter) {
        try {
            Object value = XposedHelpers.callMethod(target, getter);
            return value == null ? "" : String.valueOf(value);
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String firstNonEmpty(String... values) {
        if (values == null) return "";
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) return value.trim();
        }
        return "";
    }

    private static String ticketAccountId(String ticket) {
        try {
            return new JSONObject(ticket).optString("accountId", "");
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String failureMessage(Object value) {
        if (value == null) return "";
        try {
            Object desc = XposedHelpers.getObjectField(value, "desc");
            if (desc != null && !String.valueOf(desc).trim().isEmpty()) {
                return String.valueOf(desc).trim();
            }
        } catch (Throwable ignored) {
        }
        return String.valueOf(value);
    }

    @Override
    public void onAuthorizationCreated(final String state) {
        mainHandler.post(() -> {
            launchHost();
            showNextConfirmation();
            mainHandler.postDelayed(() -> expire(state), BridgeContract.REQUEST_TTL_MS);
        });
    }

    @Override
    public void onAuthorizationCancelled(final String state) {
        mainHandler.post(() -> {
            dismissConfirmation(state);
            showNextConfirmation();
        });
    }

    @Override
    public String hostVersion() {
        Context context = AndroidAppHelper.currentApplication();
        if (context == null) return "unknown";
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(
                    BridgeContract.HOST_PACKAGE, 0);
            return info.versionName == null ? "unknown" : info.versionName;
        } catch (Throwable error) {
            return "unknown";
        }
    }

    private void showCredentialPreview(AuthorizationSnapshot snapshot) {
        if (snapshot == null || !snapshot.isTerminal()) return;
        Context context = AndroidAppHelper.currentApplication();
        if (context == null) return;
        // One result opens one preview even if the SDK delivers a duplicate.
        String previewKey = snapshot.state + ":" + snapshot.status + ":" + snapshot.expiresAt;
        if (lastPreviewState.equals(previewKey)) return;
        lastPreviewState = previewKey;
        try {
            Intent preview = new Intent();
            preview.setClassName(BridgeContract.MODULE_PACKAGE,
                    BridgeContract.MODULE_PACKAGE + ".MainActivity");
            preview.setAction(MainActivity.ACTION_SHOW_CREDENTIAL);
            preview.putExtra(MainActivity.EXTRA_STATE, snapshot.state);
            if (snapshot.accountSession != null) {
                preview.putExtra(MainActivity.EXTRA_SESSION, snapshot.accountSession);
            }
            String origin = registry.originForInternal(snapshot.state);
            preview.putExtra(MainActivity.EXTRA_ORIGIN,
                    origin == null ? "小天才家长端 · 原生微信登录" : origin);
            preview.putExtra(MainActivity.EXTRA_WECHAT_ERROR_CODE, snapshot.wechatErrorCode);
            preview.putExtra(MainActivity.EXTRA_WECHAT_ERROR, snapshot.wechatError);
            preview.putExtra(MainActivity.EXTRA_CAPTURED_AT, System.currentTimeMillis());
            preview.putExtra(MainActivity.EXTRA_HOST_VERSION, hostVersion());
            preview.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            context.startActivity(preview);
        } catch (Throwable error) {
            XposedBridge.log(TAG + "preview could not be opened: "
                    + error.getClass().getSimpleName());
        }
    }

    private void launchHost() {
        long now = System.currentTimeMillis();
        if (now - lastHostLaunch < 3_000L) return;
        Context context = AndroidAppHelper.currentApplication();
        if (context == null) return;
        try {
            Intent intent = context.getPackageManager().getLaunchIntentForPackage(
                    BridgeContract.HOST_PACKAGE);
            if (intent == null) return;
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            context.startActivity(intent);
            lastHostLaunch = now;
        } catch (Throwable error) {
            // Android can reject background activity starts. The polling page
            // keeps waiting_for_host, so the user can open XTC manually.
            XposedBridge.log(TAG + "host launch deferred: " + error.getClass().getSimpleName());
        }
    }

    private void showNextConfirmation() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(this::showNextConfirmation);
            return;
        }
        if (confirmationDialog != null) return;
        AuthorizationSnapshot request = registry.nextPending(System.currentTimeMillis());
        if (request == null) return;
        Activity activity = resumedActivity.get();
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            launchHost();
            scheduleConfirmationRetry();
            return;
        }
        if (!registry.mark(request.state, AuthorizationSnapshot.WAITING_FOR_HOST,
                AuthorizationSnapshot.AWAITING_CONFIRMATION, System.currentTimeMillis())) {
            return;
        }

        confirmationState = request.state;
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("允许网页使用微信授权？")
                .setMessage("请求来源：\n" + safeOrigin(registry.originForInternal(request.state))
                        + "\n\n确认后将打开微信。授权结果只返回给这个来源，且只能读取一次。")
                .setNegativeButton("拒绝", (ignored, which) ->
                        reject(request.state, "user_denied", "你已拒绝这个网页的授权请求。"))
                .setPositiveButton("允许并打开微信", (ignored, which) ->
                        sendWechatAuthorization(request.state))
                .setOnCancelListener(ignored ->
                        reject(request.state, "user_denied", "你已取消这个网页的授权请求。"))
                .create();
        confirmationDialog = dialog;
        dialog.setOnDismissListener(ignored -> {
            if (confirmationDialog == dialog) {
                confirmationDialog = null;
                confirmationState = null;
            }
        });
        try {
            dialog.show();
        } catch (Throwable error) {
            confirmationDialog = null;
            confirmationState = null;
            registry.fail(request.state, "host_ui_unavailable",
                    "小天才当前页面无法显示确认框，请打开小天才后重试。",
                    System.currentTimeMillis());
        }
    }

    private static String safeOrigin(String origin) {
        return origin == null ? "未知来源（请勿继续）" : origin;
    }

    private void scheduleConfirmationRetry() {
        if (retryScheduled) return;
        retryScheduled = true;
        mainHandler.postDelayed(() -> {
            retryScheduled = false;
            showNextConfirmation();
        }, 700L);
    }

    private void reject(String state, String code, String message) {
        registry.fail(state, code, message, System.currentTimeMillis());
        dismissConfirmation(state);
    }

    private void dismissConfirmation(String state) {
        if (confirmationState == null || !confirmationState.equals(state)) return;
        AlertDialog dialog = confirmationDialog;
        confirmationDialog = null;
        confirmationState = null;
        if (dialog != null && dialog.isShowing()) dialog.dismiss();
    }

    private void expire(String state) {
        if (registry.fail(state, "expired", "授权请求已过期，请重新发起。",
                System.currentTimeMillis())) {
            dismissConfirmation(state);
        }
    }

    private void sendWechatAuthorization(String state) {
        if (!registry.mark(state, AuthorizationSnapshot.AWAITING_CONFIRMATION,
                AuthorizationSnapshot.LAUNCHING_WECHAT, System.currentTimeMillis())) {
            return;
        }
        try {
            Class<?> managerClass = Class.forName(
                    "com.xtc.common.util.WXBaseManager", false, hostClassLoader);
            Object manager = XposedHelpers.callStaticMethod(managerClass, "getInstance");
            Object api = XposedHelpers.callMethod(manager, "getApi");
            if (api == null) throw new IllegalStateException("XTC WeChat API is null");
            boolean installed = (Boolean) XposedHelpers.callMethod(api, "isWXAppInstalled");
            if (!installed) {
                registry.fail(state, "wechat_not_installed", "手机上没有可用的微信。",
                        System.currentTimeMillis());
                return;
            }

            Class<?> requestClass = Class.forName(
                    "com.tencent.mm.opensdk.modelmsg.SendAuth$Req", true, hostClassLoader);
            Object request = XposedHelpers.newInstance(requestClass);
            XposedHelpers.setObjectField(request, "scope", "snsapi_userinfo");
            XposedHelpers.setObjectField(request, "state", BridgeContract.wireState(state));
            XposedHelpers.setObjectField(request, "transaction",
                    "jzwb" + System.currentTimeMillis());
            registry.mark(state, AuthorizationSnapshot.LAUNCHING_WECHAT,
                    AuthorizationSnapshot.WAITING_FOR_WECHAT, System.currentTimeMillis());
            boolean sent = (Boolean) XposedHelpers.callMethod(api, "sendReq", request);
            if (!sent) {
                registry.fail(state, "wechat_launch_failed",
                        "微信没有接受授权请求，请确认微信可用后重试。",
                        System.currentTimeMillis());
            }
        } catch (Throwable error) {
            XposedBridge.log(TAG + "sendReq failed: " + error);
            registry.fail(state, "host_incompatible",
                    "当前小天才版本的微信接口不兼容，请查看 LSPosed 日志。",
                    System.currentTimeMillis());
        }
    }

}

