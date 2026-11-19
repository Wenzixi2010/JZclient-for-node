package com.zxi2233.wechatbridge;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.AndroidAppHelper;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.os.Handler;
import android.os.Looper;

import java.lang.ref.WeakReference;

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

    BridgeRuntime(ClassLoader hostClassLoader) {
        this.hostClassLoader = hostClassLoader;
    }

    void install() throws Throwable {
        hookWechatCallback();
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
                        String state = (String) XposedHelpers.getObjectField(response, "state");
                        if (state == null || !state.startsWith(BridgeContract.STATE_PREFIX)) return;

                        int errorCode = XposedHelpers.getIntField(response, "errCode");
                        String error = (String) XposedHelpers.getObjectField(response, "errStr");
                        String code = (String) XposedHelpers.getObjectField(response, "code");
                        boolean owned = registry.completeWechat(
                                state, code, errorCode, error, System.currentTimeMillis());
                        if (!owned) return;

                        // The host must not consume this single-use code through
                        // its own wechat.login.response broadcast.
                        param.setResult(null);
                        showCredentialPreview(registry.peek(state, System.currentTimeMillis()));
                        if (param.thisObject instanceof Activity) {
                            ((Activity) param.thisObject).finish();
                        }
                        XposedBridge.log(TAG + "received WeChat result, errCode=" + errorCode);
                    }
                });
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
        if (lastPreviewState.equals(snapshot.state + ":" + snapshot.status)) return;
        lastPreviewState = snapshot.state + ":" + snapshot.status;
        try {
            Intent preview = new Intent();
            preview.setClassName(BridgeContract.MODULE_PACKAGE,
                    BridgeContract.MODULE_PACKAGE + ".MainActivity");
            preview.setAction(MainActivity.ACTION_SHOW_CREDENTIAL);
            preview.putExtra(MainActivity.EXTRA_STATE, snapshot.state);
            String secret = registry.captureSecret(snapshot);
            if (secret != null) preview.putExtra(MainActivity.EXTRA_CODE, secret);
            preview.putExtra(MainActivity.EXTRA_ORIGIN, registry.originForInternal(snapshot.state));
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

