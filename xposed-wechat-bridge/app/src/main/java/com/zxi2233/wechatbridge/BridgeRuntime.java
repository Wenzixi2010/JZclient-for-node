package com.zxi2233.wechatbridge;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.AndroidAppHelper;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.lang.reflect.Proxy;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Coordinates user confirmation, the host WeChat SDK and the local server. */
final class BridgeRuntime implements LoopbackHttpServer.Listener {
    private static final String TAG = "[JzLoginBridge] ";

    private final ClassLoader hostClassLoader;
    private final RequestRegistry registry = new RequestRegistry();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final List<String> diagnosticLines = new ArrayList<>();

    private WeakReference<Activity> resumedActivity = new WeakReference<>(null);
    private AlertDialog confirmationDialog;
    private String confirmationState;
    private boolean retryScheduled;
    private long lastHostLaunch;
    private String lastPreviewState = "";
    private final AtomicBoolean hostLoginInFlight = new AtomicBoolean(false);
    private volatile String pendingOfficialState;
    private volatile String hostSessionJson;
    private BroadcastReceiver officialLoginReceiver;
    private volatile boolean wxApiReady;
    private int officialHostSessionRetries;
    private volatile long confirmationGraceUntil;
    private String activeTransferredState;

    private void recordDiagnostic(String message) {
        String line = System.currentTimeMillis() + " " + message;
        XposedBridge.log(TAG + line);
        synchronized (diagnosticLines) {
            diagnosticLines.add(line);
            if (diagnosticLines.size() > 80) {
                Iterator<String> iterator = diagnosticLines.iterator();
                iterator.next();
                iterator.remove();
            }
        }
    }

    @Override
    public JSONObject diagnostics() {
        try {
            return new JSONObject()
                    .put("hostVersion", hostVersion())
                    .put("loginInFlight", hostLoginInFlight.get())
                    .put("officialRequestPending", pendingOfficialState != null)
                    .put("hasCapturedSession", hostSessionJson != null);
        } catch (Throwable ignored) {
            return new JSONObject();
        }
    }

    @Override
    public String diagnosticLog() {
        synchronized (diagnosticLines) {
            StringBuilder builder = new StringBuilder();
            for (String line : diagnosticLines) {
                if (builder.length() > 0) builder.append('\n');
                builder.append(line);
            }
            return builder.toString();
        }
    }

    BridgeRuntime(ClassLoader hostClassLoader) {
        this.hostClassLoader = hostClassLoader;
    }

    void install() throws Throwable {
        hookWechatCallback();
        hookOfficialSessionStore();
        hookWechatApiInitialization();
        hookHostLaunchIntent();
        scheduleOfficialLoginReceiver();
        hookActivities();
        LoopbackHttpServer server = new LoopbackHttpServer(registry, this);
        server.start();
    }

    /**
     * The module starts XTC's launcher activity with a private one-time action.
     * Reading the state on the lifecycle callback avoids consuming it during
     * the launcher's normal launch path.
     */
    private void hookHostLaunchIntent() {
        XposedHelpers.findAndHookMethod(Activity.class, "onResume",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Activity activity = (Activity) param.thisObject;
                        if (activity.getClass().getName().startsWith(
                                BridgeContract.HOST_PACKAGE + ".")) {
                            acceptHostLaunchIntent(activity);
                        }
                    }
                });
    }

    private void acceptHostLaunchIntent(Activity activity) {
        Intent intent = activity.getIntent();
        if (intent == null
                || !BridgeContract.HOST_AUTH_ACTION.equals(intent.getAction())) {
            return;
        }
        String state = intent.getStringExtra(
                BridgeContract.EXTRA_DEEP_LINK_STATE);
        if (!BridgeContract.isValidState(state)) {
            recordDiagnostic("host launch intent contained an invalid state");
            intent.removeExtra(BridgeContract.EXTRA_DEEP_LINK_STATE);
            intent.removeExtra(BridgeContract.EXTRA_DEEP_LINK_ORIGIN);
            return;
        }
        try {
            AuthorizationSnapshot snapshot = registry.acceptTransfer(
                    state, System.currentTimeMillis());
            recordDiagnostic("host launch intent accepted, status="
                    + snapshot.status);
            activeTransferredState = state;
            recordDiagnostic("waiting for browser to bind transferred request");
        } catch (BridgeException error) {
            recordDiagnostic("host launch intent rejected: " + error.code);
            registry.fail(state, error.code, error.getMessage(),
                    System.currentTimeMillis());
            mainHandler.post(this::launchHost);
        } finally {
            // Never leave a one-time correlation value on a long-lived
            // launcher intent after the in-memory request is established.
            intent.removeExtra(BridgeContract.EXTRA_DEEP_LINK_STATE);
            intent.removeExtra(BridgeContract.EXTRA_DEEP_LINK_ORIGIN);
            intent.setData(null);
        }
    }

    private void launchTransferredLoginWhenReady(final String state) {
        if (!state.equals(activeTransferredState)) return;
        if (!wxApiReady) {
            scheduleTransferredLoginRetry(state);
            return;
        }
        AuthorizationSnapshot current = registry.nextPending(
                System.currentTimeMillis());
        if (current == null || !state.equals(current.state)) return;
        if (!registry.mark(state, AuthorizationSnapshot.WAITING_FOR_HOST,
                AuthorizationSnapshot.AWAITING_CONFIRMATION,
                System.currentTimeMillis())) {
            return;
        }
        if (!registry.mark(state, AuthorizationSnapshot.AWAITING_CONFIRMATION,
                AuthorizationSnapshot.LAUNCHING_WECHAT,
                System.currentTimeMillis())) {
            return;
        }
        startOfficialWechat(state);
    }

    private void scheduleTransferredLoginRetry(final String state) {
        if (!state.equals(activeTransferredState)) return;
        mainHandler.postDelayed(
                () -> launchTransferredLoginWhenReady(state), 200L);
    }

    private void hookWechatApiInitialization() {
        try {
            Class<?> managerClass = loadHostClass(
                    "com.xtc.common.util.WXBaseManager");
            Class<?> apiClass = loadHostClass(
                    "com.tencent.mm.opensdk.openapi.IWXAPI");
            XposedHelpers.findAndHookMethod(managerClass, "registerApp",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object api = XposedHelpers.callMethod(
                                        param.thisObject, "getApi");
                                wxApiReady = api != null
                                        && apiClass.isInstance(api);
                                recordDiagnostic("WeChat API initialization observed, ready="
                                        + wxApiReady);
                            } catch (Throwable error) {
                                recordDiagnostic("WeChat API readiness check failed: " + error);
                            }
                        }
                    });
            recordDiagnostic("WeChat API initialization hook installed");
            try {
                Object manager = XposedHelpers.callStaticMethod(
                        managerClass, "getInstance");
                Object api = XposedHelpers.callMethod(manager, "getApi");
                if (api != null && apiClass.isInstance(api)) {
                    wxApiReady = true;
                    recordDiagnostic("WeChat API was already initialized");
                }
            } catch (Throwable error) {
                recordDiagnostic("existing WeChat API probe skipped: "
                        + error.getClass().getSimpleName());
            }
        } catch (Throwable error) {
            recordDiagnostic("WeChat API initialization hook unavailable: " + error);
        }
    }

    private void scheduleOfficialLoginReceiver() {
        if (officialLoginReceiver != null) return;
        mainHandler.postDelayed(this::installOfficialLoginReceiver, 300L);
    }

    private void installOfficialLoginReceiver() {
        if (officialLoginReceiver != null) return;
        try {
            Class<?> activityClass = loadHostClass(
                    "com.xtc.watch.wxapi.WXEntryActivity");
            String action = (String) XposedHelpers.getStaticObjectField(
                    activityClass, "AuX");
            String extra = (String) XposedHelpers.getStaticObjectField(
                    activityClass, "aUX");
            Context context = AndroidAppHelper.currentApplication();
            if (context == null) {
                mainHandler.postDelayed(this::installOfficialLoginReceiver, 300L);
                return;
            }
            officialLoginReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context receiverContext, Intent intent) {
                    String encoded = intent == null
                            ? null : intent.getStringExtra(extra);
                    if (encoded == null || encoded.isEmpty()) {
                        recordDiagnostic("official broadcast missing response");
                        return;
                    }
                    try {
                        Class<?> codecClass = loadHostClass(
                                "com.xtc.watch.util.Uganda");
                        Class<?> responseClass = loadHostClass(
                                "com.tencent.mm.opensdk.modelmsg.SendAuth$Resp");
                        Object response = XposedHelpers.callStaticMethod(
                                codecClass, "Gabon", encoded, responseClass);
                        if (response == null) {
                            recordDiagnostic("official broadcast decoded to null");
                            return;
                        }
                        String wireState = (String) XposedHelpers.getObjectField(
                                response, "state");
                        int errorCode = XposedHelpers.getIntField(
                                response, "errCode");
                        String error = (String) XposedHelpers.getObjectField(
                                response, "errStr");
                        String code = (String) XposedHelpers.getObjectField(
                                response, "code");
                        String officialState = pendingOfficialState;
                        boolean officialRequest = officialState != null
                                && (wireState == null
                                || "login_state".equals(wireState));
                        boolean bridgeRequest = registry.matches(wireState);
                        if (!officialRequest && !bridgeRequest) return;
                        boolean owned = officialRequest
                                ? registry.claimOfficialWechat(
                                        officialState, errorCode, error, System.currentTimeMillis())
                                : registry.claimWechat(
                                        wireState, errorCode, error, System.currentTimeMillis());
                        if (!owned) return;
                        recordDiagnostic("wx broadcast received, official="
                                + officialRequest + ", errCode=" + errorCode
                                + ", hasCode=" + (code != null && !code.isEmpty()));
                        if (errorCode != 0) {
                            pendingOfficialState = null;
                            releaseHostLogin();
                        }
                    } catch (Throwable error) {
                        recordDiagnostic("official broadcast parse failed: " + error);
                    }
                }
            };
            context.registerReceiver(officialLoginReceiver,
                    new IntentFilter(action), Context.RECEIVER_EXPORTED);
            recordDiagnostic("official login broadcast receiver installed");
        } catch (Throwable error) {
            officialLoginReceiver = null;
            recordDiagnostic("official login broadcast receiver unavailable: " + error);
        }
    }

    private void hookActivities() {
        hookHostOnCreate();
        XposedHelpers.findAndHookMethod(Activity.class, "onResume", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                resumedActivity = new WeakReference<>((Activity) param.thisObject);
                scheduleOfficialLoginReceiver();
                ensureWechatApiReady();
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

    private void hookHostOnCreate() {
        XposedHelpers.findAndHookMethod(Activity.class, "onCreate",
                Bundle.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (!"com.xtc.watch.wxapi.WXEntryActivity".equals(
                                param.thisObject.getClass().getName())) {
                            return;
                        }
                        // BaseWXEntryActivity registers its private finish
                        // receiver from the native onCreate constructor. Give
                        // that receiver a chance to register before the SDK
                        // immediately delivers a pending callback.
                        recordDiagnostic("WXEntryActivity created");
                    }
                });
    }

    private void ensureWechatApiReady() {
        Context context = AndroidAppHelper.currentApplication();
        if (context == null) {
            mainHandler.postDelayed(this::ensureWechatApiReady, 300L);
            return;
        }
        try {
            Class<?> managerClass = loadHostClass(
                    "com.xtc.common.util.WXBaseManager");
            Class<?> apiClass = loadHostClass(
                    "com.tencent.mm.opensdk.openapi.IWXAPI");
            Object manager = XposedHelpers.callStaticMethod(
                    managerClass, "getInstance");
            Object api = XposedHelpers.callMethod(manager, "getApi");
            wxApiReady = api != null && apiClass.isInstance(api);
            if (wxApiReady) {
                recordDiagnostic("WeChat API ready on resumed host activity");
            } else {
                recordDiagnostic("WeChat API remained unavailable");
            }
        } catch (Throwable error) {
            recordDiagnostic("WeChat API initialization on resume failed: "
                    + Log.getStackTraceString(error));
        }
    }

    private void loadExistingHostSession() {
        if (pendingOfficialState == null) return;
        Context context = AndroidAppHelper.currentApplication();
        if (context == null) return;
        try {
            SharedPreferences preferences = context.getSharedPreferences(
                    context.getPackageName(), Context.MODE_PRIVATE);
            String value = preferences.getString(
                    "mobile_account_login_status", null);
            if (value == null || value.isEmpty()) return;
            if (parseHostSession(value)) {
                recordDiagnostic("loaded persisted host account session");
            } else {
                retryLoadPersistedHostSession();
            }
        } catch (Throwable error) {
            recordDiagnostic("persisted host session unavailable: "
                    + error.getClass().getSimpleName());
            retryLoadPersistedHostSession();
        }
    }

    private void retryLoadPersistedHostSession() {
        if (pendingOfficialState == null || officialHostSessionRetries >= 20) return;
        officialHostSessionRetries++;
        mainHandler.postDelayed(this::loadExistingHostSession, 150L);
    }

    private boolean parseHostSession(String value) throws Exception {
        JSONObject record = new JSONObject(value);
        JSONObject account = record.optJSONObject("MobileAccount");
        if (account == null) account = record;
        String token = account.optString("token", "");
        if (token.isEmpty()) return false;
        String mobileId = account.optString("mobileId", "");
        JSONObject session = new JSONObject()
                .put("accountId", account.optString("mobileId", ""))
                .put("authId", account.optString("authId", ""))
                .put("mobileId", account.optString("mobileId", ""))
                .put("ticket", token)
                .put("userName", firstNonEmpty(
                        account.optString("name", ""),
                        account.optString("number", ""),
                        account.optString("mobileId", "")))
                .put("mobileNumber", account.optString("number", ""))
                .put("avatar", account.optString("icon", ""))
                .put("ssoAccountId", ticketAccountId(token));
        attachHostBusinessIdentity(session, mobileId);
        hostSessionJson = session.toString();
        return true;
    }

    private void hookOfficialSessionStore() {
        try {
            Class<?> sharedToolClass = Class.forName(
                    "com.xtc.common.shared.SharedTool", false, hostClassLoader);
            XposedHelpers.findAndHookMethod(sharedToolClass, "saveString",
                    String.class, String.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!"mobile_account_login_status".equals(param.args[0])
                                    || param.args[1] == null) return;
                            try {
                                parseHostSession(String.valueOf(param.args[1]));
                                recordDiagnostic("captured host account session");
                            } catch (Throwable error) {
                                recordDiagnostic("host session parse skipped: "
                                        + error.getClass().getSimpleName());
                            }
                        }
                    });
            recordDiagnostic("official session store hook installed");
        } catch (Throwable error) {
            recordDiagnostic("session store hook unavailable: "
                    + error.getClass().getSimpleName());
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
                        String wireState = (String) XposedHelpers.getObjectField(response, "state");
                        String officialState = pendingOfficialState;
                        boolean officialRequest = officialState != null
                                && (wireState == null || "login_state".equals(wireState));
                        boolean bridgeRequest = registry.matches(wireState);
                        if (!officialRequest && !bridgeRequest) return;

                        int errorCode = XposedHelpers.getIntField(response, "errCode");
                        String error = (String) XposedHelpers.getObjectField(response, "errStr");
                        long now = System.currentTimeMillis();
                        boolean owned = officialRequest
                                ? registry.claimOfficialWechat(
                                        officialState, errorCode, error, now)
                                : registry.claimWechat(wireState, errorCode, error, now);
                        if (!owned) return;
                        recordDiagnostic("wx callback received, official="
                                + officialRequest + ", errCode=" + errorCode);
                        if (errorCode != 0) {
                            pendingOfficialState = null;
                            mainHandler.post(BridgeRuntime.this::releaseHostLogin);
                        }
                        // Keep the original callback. BaseWXEntryActivity must
                        // broadcast it so WXAccountManager can run the host's
                        // own wc(), persistence and IM startup sequence.
                    }
                });
    }

    private void completeHostLogin(final String wireState, final Object service,
                                   final Class<?> mobileAccountClass) {
        recordDiagnostic("reading host account session");
        try {
            Object account = loadMobileAccount(service, mobileAccountClass);
            String sessionJson = hostSessionJson;
            if (account != null && mobileAccountClass.isInstance(account)) {
                String mobileId = stringField(account, "getMobileId");
                String token = stringField(account, "getToken");
                String authId = stringField(account, "getAuthId");
                if (!token.isEmpty()) {
                    JSONObject session = new JSONObject()
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
                            .put("ssoAccountId", ticketAccountId(token));
                    attachHostBusinessIdentity(session, mobileId);
                    sessionJson = session.toString();
                }
            }
            if (account == null && hostSessionJson == null) {
                if (officialHostSessionRetries < 12) {
                    officialHostSessionRetries++;
                    recordDiagnostic("host account not ready, retry "
                            + officialHostSessionRetries);
                    mainHandler.postDelayed(() -> completeHostLogin(
                            wireState, service, mobileAccountClass), 400L);
                    return;
                }
            }
            if (sessionJson == null || sessionJson.isEmpty()) {
                throw new IllegalStateException("host session is unavailable");
            }
            boolean completed = registry.completeWithAccount(
                    wireState, sessionJson, 0, null, System.currentTimeMillis());
            if (completed) {
                recordDiagnostic("host account login completed");
                String state = wireState.substring(BridgeContract.STATE_PREFIX.length());
                String origin = registry.originForInternal(state);
                AuthorizationSnapshot request = registry.peek(
                        origin, state, System.currentTimeMillis());
                if (request != null) showCredentialPreview(request);
            }
        } catch (Throwable error) {
            recordDiagnostic("reading host account failed: "
                    + error.getClass().getSimpleName());
            registry.failLogin(wireState, "session_unavailable",
                    "小天才登录成功，但无法读取账号会话。",
                    System.currentTimeMillis());
        } finally {
            pendingOfficialState = null;
            officialHostSessionRetries = 0;
            mainHandler.post(this::releaseHostLogin);
        }
    }

    private void releaseHostLogin() {
        hostLoginInFlight.set(false);
    }

    private Class<?> loadHostClass(String name) throws Throwable {
        return Class.forName(name, false, hostClassLoader);
    }

    private Object loadMobileService(Context context) throws Throwable {
        Class<?> serviceClass = loadHostClass(
                "com.xtc.account.service.impl.Gambia");
        Object service = XposedHelpers.callStaticMethod(
                serviceClass, "Yd", context);
        if (service == null) {
            throw new IllegalStateException("Gambia.Yd returned null");
        }
        return service;
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

    /**
     * The official host uses these values to identify the same Android
     * installation across SSO and its business APIs. They are separate from
     * the SSO deviceId embedded in the ticket.
     */
    private void attachHostBusinessIdentity(
            JSONObject session, String mobileId) {
        Context context = AndroidAppHelper.currentApplication();
        if (context == null || session == null || mobileId == null
                || mobileId.trim().isEmpty()) {
            return;
        }
        try {
            Class<?> deviceToolClass = loadHostClass(
                    "com.xtc.data.device.DeviceDataTool");
            Object deviceTool = XposedHelpers.callStaticMethod(
                    deviceToolClass, "getInstance", context);
            if (deviceTool == null) {
                recordDiagnostic("host business identity unavailable: no device tool");
                return;
            }
            String deviceId = callStringGetter(
                    deviceTool, "getDeviceId", mobileId);
            String mac = callStringGetter(
                    deviceTool, "getMacAddress", context);
            String machineId = callStringGetter(
                    deviceTool, "getMachineId");
            String newDeviceId = callStringGetter(
                    deviceTool, "getNewDeviceId");
            session.put("bizDeviceId", deviceId)
                    .put("bizMac", mac)
                    .put("bizMachineId", machineId)
                    .put("bizNDeviceId", firstNonEmpty(newDeviceId, deviceId))
                    .put("businessIdentity", !deviceId.isEmpty()
                            && !mac.isEmpty() && !machineId.isEmpty());
            recordDiagnostic("host business identity captured, "
                    + "device=" + (deviceId.isEmpty() ? "missing" : "present")
                    + ", mac=" + (mac.isEmpty() ? "missing" : "present")
                    + ", machine=" + (machineId.isEmpty() ? "missing" : "present")
                    + ", newDevice=" + (newDeviceId.isEmpty() ? "missing" : "present"));
        } catch (Throwable error) {
            recordDiagnostic("host business identity capture failed: "
                    + error.getClass().getSimpleName());
        }
    }

    private static String callStringGetter(
            Object target, String getter, Object... args) {
        try {
            Object value = XposedHelpers.callMethod(
                    target, getter, args);
            return value == null ? "" : String.valueOf(value);
        } catch (Throwable ignored) {
            return "";
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
            if (registry.originForInternal(state) == null) return;
            activeTransferredState = null;
            confirmationGraceUntil = Math.max(confirmationGraceUntil,
                    System.currentTimeMillis() + 1_000L);
            showNextConfirmation();
            mainHandler.postDelayed(
                    () -> expire(state), BridgeContract.REQUEST_TTL_MS);
        });
    }

    @Override
    public void onAuthorizationTransferAccepted(final String state) {
        recordDiagnostic("browser bound deep-link authorization");
        mainHandler.post(() -> {
            confirmationGraceUntil = 0L;
            if (state.equals(activeTransferredState)) {
                launchTransferredLoginWhenReady(state);
            }
        });
    }

    @Override
    public void onAuthorizationCancelled(final String state) {
        mainHandler.post(() -> {
            activeTransferredState = null;
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
        if (confirmationGraceUntil > System.currentTimeMillis()) {
            scheduleConfirmationRetry();
            return;
        }
        loadExistingHostSession();
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
        }, 250L);
    }

    private void reject(String state, String code, String message) {
        if (state.equals(activeTransferredState)) activeTransferredState = null;
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
        if (state.equals(activeTransferredState)) activeTransferredState = null;
        if (registry.fail(state, "expired", "授权请求已过期，请重新发起。",
                System.currentTimeMillis())) {
            dismissConfirmation(state);
        }
    }

    private void sendWechatAuthorization(String state) {
        if (!registry.mark(state, AuthorizationSnapshot.AWAITING_CONFIRMATION,
                AuthorizationSnapshot.LAUNCHING_WECHAT,
                System.currentTimeMillis())) {
            return;
        }
        startOfficialWechat(state);
    }

    private void startOfficialWechat(String state) {
        Activity activity = resumedActivity.get();
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            registry.fail(state, "host_ui_unavailable",
                    "小天才当前页面不可用，请回到小天才后重试。",
                    System.currentTimeMillis());
            activeTransferredState = null;
            return;
        }
        if (!wxApiReady) {
            registry.fail(state, "host_not_ready",
                    "小天才刚启动，微信组件还未初始化完成，请稍后重试。",
                    System.currentTimeMillis());
            return;
        }
        if (!hostLoginInFlight.compareAndSet(false, true)) {
            registry.fail(state, "login_busy",
                    "小天才正在完成另一个登录，请稍后重试。",
                    System.currentTimeMillis());
            return;
        }
        try {
            Class<?> accountManagerClass = loadHostClass(
                    "com.xtc.account.thirdlogin.WXAccountManager");
            Object accountManager = XposedHelpers.getStaticObjectField(
                    accountManagerClass, "Hawaii");
            if (accountManager == null) {
                throw new IllegalStateException(
                        "WXAccountManager singleton is unavailable");
            }
            Object listener = createHostLoginListener(state);
            pendingOfficialState = state;
            registry.mark(state, AuthorizationSnapshot.LAUNCHING_WECHAT,
                    AuthorizationSnapshot.WAITING_FOR_WECHAT, System.currentTimeMillis());
            XposedHelpers.callMethod(
                    accountManager, "Ukraine", activity, listener);
            recordDiagnostic("delegated authorization to WXAccountManager");
        } catch (Throwable error) {
            recordDiagnostic("WXAccountManager launch failed: "
                    + Log.getStackTraceString(error));
            registry.fail(state, "host_incompatible",
                    "小天才官方微信登录未能启动，请查看 LSPosed 日志。",
                    System.currentTimeMillis());
            pendingOfficialState = null;
            activeTransferredState = null;
            releaseHostLogin();
        }
    }

    private Object createHostLoginListener(final String state) throws Throwable {
        final Class<?> listenerClass = loadHostClass(
                "com.xtc.component.api.account.callback.OnLoginListener");
        return Proxy.newProxyInstance(listenerClass.getClassLoader(),
                new Class<?>[]{listenerClass}, (proxy, method, args) -> {
                    String name = method.getName();
                    if ("onSuccess".equals(name)) {
                        recordDiagnostic("official host login success");
                        officialHostSessionRetries = 0;
                        Context context = AndroidAppHelper.currentApplication();
                        if (context == null) {
                            registry.fail(state, "host_context_unavailable",
                                    "小天才登录成功，但应用上下文已经不可用。",
                                    System.currentTimeMillis());
                            pendingOfficialState = null;
                            releaseHostLogin();
                            return null;
                        }
                        try {
                            Object service = loadMobileService(context);
                            Class<?> mobileAccountClass = loadHostClass(
                                    "com.xtc.component.api.account.bean.MobileAccount");
                            mainHandler.postDelayed(() -> completeHostLogin(
                                    BridgeContract.wireState(state),
                                    service, mobileAccountClass), 300L);
                        } catch (Throwable error) {
                            recordDiagnostic("host account service unavailable: "
                                    + error.getClass().getSimpleName());
                            registry.fail(state, "session_unavailable",
                                    "小天才登录成功，但无法读取账号会话。",
                                    System.currentTimeMillis());
                            pendingOfficialState = null;
                            releaseHostLogin();
                        }
                        return null;
                    }
                    if ("onFailed".equals(name)) {
                        Object failure = args != null && args.length > 0 ? args[0] : null;
                        String message = failureMessage(failure);
                        recordDiagnostic("official host login failed: " + message);
                        registry.fail(state, "host_login_failed",
                                message.isEmpty() ? "小天才未能完成微信登录。"
                                        : "小天才未能完成微信登录：" + message,
                                System.currentTimeMillis());
                        pendingOfficialState = null;
                        mainHandler.post(BridgeRuntime.this::releaseHostLogin);
                        return null;
                    }
                    if ("hashCode".equals(name)) return System.identityHashCode(proxy);
                    if ("equals".equals(name)) return proxy == args[0];
                    if ("toString".equals(name)) return "JZOfficialWechatLoginListener";
                    return null;
                });
    }

}

