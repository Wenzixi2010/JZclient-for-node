package com.zxi2233.wechatbridge;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;

/** Local-only preview of the most recent result intercepted by the module. */
public final class MainActivity extends Activity {
    public static final String ACTION_SHOW_CREDENTIAL =
            "com.zxi2233.wechatbridge.action.SHOW_CREDENTIAL";
    public static final String EXTRA_STATE = "credential_state";
    public static final String EXTRA_CODE = "credential_code";
    public static final String EXTRA_ORIGIN = "credential_origin";
    public static final String EXTRA_WECHAT_ERROR_CODE = "wechat_error_code";
    public static final String EXTRA_WECHAT_ERROR = "wechat_error";
    public static final String EXTRA_CAPTURED_AT = "captured_at";
    public static final String EXTRA_HOST_VERSION = "host_version";

    private TextView connectionPill;
    private TextView heading;
    private TextView summary;
    private TextView codeView;
    private TextView stateView;
    private TextView originView;
    private TextView errorView;
    private TextView capturedAtView;
    private TextView hostView;
    private LinearLayout credentialCard;
    private Button revealButton;
    private Button copyCodeButton;
    private Button copyStateButton;
    private String credentialCode;
    private String credentialState;
    private String captureState;
    private String captureCode;
    private String captureOrigin;
    private String captureWechatError;
    private int captureWechatErrorCode;
    private long captureTime;
    private String captureHostVersion;
    private boolean revealed;

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        // The actual code stays in memory for this Activity instance only.
        outState.putBoolean("code_revealed", revealed);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        configureWindow();
        setContentView(buildContent());
        consumeIntent(getIntent());
        if (savedInstanceState != null && !credentialCode.isEmpty()) {
            revealed = savedInstanceState.getBoolean("code_revealed", false);
            codeView.setText(revealed ? credentialCode : mask(credentialCode));
            revealButton.setText(revealed ? "隐藏授权码" : "显示授权码");
            renderCredential();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        consumeIntent(intent);
    }

    private void configureWindow() {
        Window window = getWindow();
        window.setStatusBarColor(color(0xFF101318));
        window.setNavigationBarColor(color(0xFF101318));
        if (Build.VERSION.SDK_INT >= 26) {
            window.getDecorView().setSystemUiVisibility(0);
        }
    }

    private View buildContent() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setBackgroundColor(color(0xFF101318));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(24), dp(20), dp(32));
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout topRow = new LinearLayout(this);
        topRow.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(topRow, rowParams());

        TextView moduleLabel = text("LSPOSED 模块", 12, 0xFF003258, Typeface.BOLD);
        moduleLabel.setPadding(dp(12), dp(6), dp(12), dp(6));
        moduleLabel.setBackground(rounded(0xFF9ECBFF, 100));
        topRow.addView(moduleLabel);

        connectionPill = text("本地监听已就绪", 12, 0xFFC8E6C9, Typeface.BOLD);
        connectionPill.setPadding(dp(12), dp(6), dp(12), dp(6));
        connectionPill.setBackground(rounded(0xFF26352B, 100));
        LinearLayout.LayoutParams pillParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pillParams.leftMargin = dp(8);
        topRow.addView(connectionPill, pillParams);

        heading = text("微信授权凭证", 32, 0xFFE7E8EC, Typeface.NORMAL);
        LinearLayout.LayoutParams headingParams = fullWidth();
        headingParams.topMargin = dp(28);
        root.addView(heading, headingParams);

        TextView description = text(
                "这里显示模块最近一次从 SendAuth.Resp 捕获的真实结果。凭证只保留在当前页面内存中。",
                16, 0xFFAEB2BD, Typeface.NORMAL);
        description.setLineSpacing(dp(4), 1f);
        LinearLayout.LayoutParams descriptionParams = fullWidth();
        descriptionParams.topMargin = dp(8);
        root.addView(description, descriptionParams);

        LinearLayout statusCard = card(0xFF1A1E25);
        LinearLayout.LayoutParams statusParams = fullWidth();
        statusParams.topMargin = dp(24);
        root.addView(statusCard, statusParams);

        summary = text("等待网页发起授权请求", 20, 0xFFE7E8EC, Typeface.BOLD);
        statusCard.addView(summary);

        TextView hint = text(
                "打开小程序或网页后点击微信授权。捕获成功时，这个界面会自动更新。",
                15, 0xFFAEB2BD, Typeface.NORMAL);
        hint.setLineSpacing(dp(3), 1f);
        LinearLayout.LayoutParams hintParams = fullWidth();
        hintParams.topMargin = dp(8);
        statusCard.addView(hint, hintParams);

        credentialCard = card(0xFF1A1E25);
        LinearLayout.LayoutParams credentialParams = fullWidth();
        credentialParams.topMargin = dp(16);
        root.addView(credentialCard, credentialParams);

        TextView codeLabel = label("授权码 CODE");
        credentialCard.addView(codeLabel);

        codeView = text("尚未捕获", 18, 0xFFE7E8EC, Typeface.NORMAL);
        codeView.setTypeface(Typeface.MONOSPACE);
        codeView.setTextIsSelectable(true);
        codeView.setPadding(dp(16), dp(16), dp(16), dp(16));
        codeView.setBackground(rounded(0xFF11151B, 16));
        codeView.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        LinearLayout.LayoutParams codeParams = fullWidth();
        codeParams.topMargin = dp(10);
        credentialCard.addView(codeView, codeParams);

        LinearLayout codeActions = new LinearLayout(this);
        codeActions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams codeActionsParams = fullWidth();
        codeActionsParams.topMargin = dp(12);
        credentialCard.addView(codeActions, codeActionsParams);

        revealButton = actionButton("显示授权码", false);
        revealButton.setEnabled(false);
        revealButton.setOnClickListener(view -> toggleCodeVisibility());
        codeActions.addView(revealButton, weightedButton());

        copyCodeButton = actionButton("复制 CODE", true);
        copyCodeButton.setEnabled(false);
        copyCodeButton.setOnClickListener(view -> copyCredential(credentialCode, "授权码已复制"));
        LinearLayout.LayoutParams copyCodeParams = weightedButton();
        copyCodeParams.leftMargin = dp(8);
        codeActions.addView(copyCodeButton, copyCodeParams);

        TextView detailsLabel = label("授权详情");
        LinearLayout.LayoutParams detailsLabelParams = fullWidth();
        detailsLabelParams.topMargin = dp(24);
        credentialCard.addView(detailsLabel, detailsLabelParams);

        stateView = detailRow(credentialCard, "STATE", "—");
        originView = detailRow(credentialCard, "请求来源", "—");
        errorView = detailRow(credentialCard, "微信错误", "—");
        capturedAtView = detailRow(credentialCard, "捕获时间", "—");
        hostView = detailRow(credentialCard, "小天才版本", "—");

        copyStateButton = actionButton("复制 STATE", false);
        copyStateButton.setEnabled(false);
        copyStateButton.setOnClickListener(view -> copyCredential(credentialState, "state 已复制"));
        LinearLayout.LayoutParams copyStateParams = fullWidth();
        copyStateParams.topMargin = dp(16);
        credentialCard.addView(copyStateButton, copyStateParams);

        LinearLayout footer = card(0xFF17201F);
        LinearLayout.LayoutParams footerParams = fullWidth();
        footerParams.topMargin = dp(16);
        root.addView(footer, footerParams);

        TextView securityTitle = text("凭证不会落盘", 16, 0xFFC8E6C9, Typeface.BOLD);
        footer.addView(securityTitle);
        TextView security = text(
                "模块不会把 code 或 state 写入日志和文件。关闭界面或再次捕获后，旧内容会从当前内存中清除。",
                14, 0xFFAEB2BD, Typeface.NORMAL);
        security.setLineSpacing(dp(3), 1f);
        LinearLayout.LayoutParams securityParams = fullWidth();
        securityParams.topMargin = dp(8);
        footer.addView(security, securityParams);

        Button clear = actionButton("清空当前凭证", false);
        clear.setOnClickListener(view -> clearCredential());
        LinearLayout.LayoutParams clearParams = fullWidth();
        clearParams.topMargin = dp(16);
        root.addView(clear, clearParams);

        getWindow().setFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE,
                android.view.WindowManager.LayoutParams.FLAG_SECURE);
        return scroll;
    }

    private void consumeIntent(Intent intent) {
        if (intent == null
                || !ACTION_SHOW_CREDENTIAL.equals(intent.getAction())
                && !intent.hasExtra(EXTRA_CODE)
                && !intent.hasExtra(EXTRA_STATE)) {
            return;
        }
        String code = safeExtra(intent, EXTRA_CODE);
        String state = safeExtra(intent, EXTRA_STATE);
        String origin = safeExtra(intent, EXTRA_ORIGIN);
        String wechatError = safeExtra(intent, EXTRA_WECHAT_ERROR);
        int wechatErrorCode = intent.getIntExtra(EXTRA_WECHAT_ERROR_CODE, 0);
        long capturedAt = intent.getLongExtra(EXTRA_CAPTURED_AT, System.currentTimeMillis());
        String hostVersion = safeExtra(intent, EXTRA_HOST_VERSION);
        intent.removeExtra(EXTRA_CODE);
        intent.removeExtra(EXTRA_STATE);

        captureCode = code;
        captureState = state;
        captureOrigin = origin;
        captureWechatError = wechatError;
        captureWechatErrorCode = wechatErrorCode;
        captureTime = capturedAt;
        captureHostVersion = hostVersion;
        revealed = false;
        renderCredential();
        // A code exists only in this Activity's memory. Drop it from the
        // recents intent representation after rendering.
        setIntent(new Intent(this, MainActivity.class));
    }

    private void renderCredential() {
        // Code and state are restored less often than a rotation; these are
        // transient fields rather than Bundle or disk state.
        credentialCode = captureCode == null ? "" : captureCode;
        credentialState = captureState == null ? "" : captureState;
        codeView.setText(credentialCode.isEmpty()
                ? "未返回授权码"
                : (revealed ? credentialCode : mask(credentialCode)));
        stateView.setText(valueOrDash(credentialState));
        originView.setText(valueOrDash(captureOrigin));
        errorView.setText(captureWechatErrorCode == 0
                ? "无（微信错误码 0）"
                : captureWechatErrorCode
                + (captureWechatError == null || captureWechatError.isEmpty()
                ? "" : " · " + captureWechatError));
        capturedAtView.setText(captureTime == 0 ? "—" : DateFormat.getDateTimeInstance(
                DateFormat.MEDIUM, DateFormat.MEDIUM, Locale.getDefault())
                .format(new Date(captureTime)));
        hostView.setText(valueOrDash(captureHostVersion));
        boolean hasCode = !credentialCode.isEmpty();
        revealButton.setEnabled(hasCode);
        copyCodeButton.setEnabled(hasCode);
        copyStateButton.setEnabled(!credentialState.isEmpty());
        if (hasCode) {
            summary.setText("已捕获微信授权凭证");
            summary.setTextColor(color(0xFF79D79B));
            connectionPill.setText("授权成功");
            connectionPill.setTextColor(color(0xFF79D79B));
            connectionPill.setBackground(rounded(0xFF21372A, 100));
        } else {
            summary.setText("微信授权未成功");
            summary.setTextColor(color(0xFFFFB4AB));
            connectionPill.setText("授权失败");
            connectionPill.setTextColor(color(0xFFFFB4AB));
            connectionPill.setBackground(rounded(0xFF3B2424, 100));
        }
    }

    private void toggleCodeVisibility() {
        revealed = !revealed;
        codeView.setText(revealed ? valueOrDash(credentialCode) : mask(credentialCode));
        revealButton.setText(revealed ? "隐藏授权码" : "显示授权码");
    }

    private void copyCredential(String value, String message) {
        if (value == null || value.isEmpty()) return;
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("JZ 微信授权", value));
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private void clearCredential() {
        credentialCode = "";
        credentialState = "";
        revealed = false;
        codeView.setText("尚未捕获");
        stateView.setText("—");
        originView.setText("—");
        errorView.setText("—");
        capturedAtView.setText("—");
        hostView.setText("—");
        revealButton.setEnabled(false);
        revealButton.setText("显示授权码");
        copyCodeButton.setEnabled(false);
        copyStateButton.setEnabled(false);
        summary.setText("等待网页发起授权请求");
        summary.setTextColor(color(0xFFE7E8EC));
        connectionPill.setText("本地监听已就绪");
        connectionPill.setTextColor(color(0xFFC8E6C9));
        connectionPill.setBackground(rounded(0xFF26352B, 100));
        setIntent(new Intent(this, MainActivity.class));
    }

    private TextView detailRow(LinearLayout parent, String label, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(14), 0, dp(14));

        TextView key = text(label, 12, 0xFFAEB2BD, Typeface.BOLD);
        row.addView(key);
        TextView content = text(value, 15, 0xFFE7E8EC, Typeface.NORMAL);
        content.setTextIsSelectable(true);
        content.setLineSpacing(dp(2), 1f);
        LinearLayout.LayoutParams contentParams = fullWidth();
        contentParams.topMargin = dp(5);
        row.addView(content, contentParams);
        parent.addView(row, fullWidth());
        return content;
    }

    private TextView label(String value) {
        return text(value, 12, 0xFFAEB2BD, Typeface.BOLD);
    }

    private LinearLayout card(int background) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(20), dp(20), dp(20), dp(20));
        card.setBackground(rounded(background, 24));
        return card;
    }

    private TextView text(String value, int sp, int color, int style) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setTypeface(Typeface.create(Typeface.SANS_SERIF, style), style);
        view.setIncludeFontPadding(false);
        return view;
    }

    private Button actionButton(String label, boolean primary) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(14);
        button.setAllCaps(false);
        button.setMinHeight(dp(48));
        button.setTextColor(primary ? color(0xFF003258) : color(0xFF9ECBFF));
        button.setBackground(rounded(primary ? 0xFF9ECBFF : 0xFF252A33, 100));
        button.setPadding(dp(16), dp(10), dp(16), dp(10));
        return button;
    }

    private int color(int value) {
        return Color.rgb(Color.red(value), Color.green(value), Color.blue(value));
    }

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private LinearLayout.LayoutParams rowParams() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams fullWidth() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams weightedButton() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private static String safeExtra(Intent intent, String name) {
        String value = intent.getStringExtra(name);
        return value == null ? "" : value;
    }

    private static String valueOrDash(String value) {
        return value == null || value.isEmpty() ? "—" : value;
    }

    private static String mask(String value) {
        if (value == null || value.isEmpty()) return "尚未捕获";
        if (value.length() <= 8) return "••••••••";
        return value.substring(0, 4) + "••••••••" + value.substring(value.length() - 4);
    }
}

