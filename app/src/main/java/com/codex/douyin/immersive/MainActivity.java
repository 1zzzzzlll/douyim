package com.codex.douyin.immersive;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import io.github.libxposed.service.XposedService;

public final class MainActivity extends Activity
        implements ModuleApplication.ServiceStateListener {
    private static final int BACKGROUND = Color.rgb(16, 17, 20);
    private static final int CARD = Color.rgb(28, 29, 34);
    private static final int PRIMARY = Color.rgb(254, 44, 85);
    private static final int TEXT_PRIMARY = Color.rgb(245, 245, 248);
    private static final int TEXT_SECONDARY = Color.rgb(169, 171, 180);

    private TextView serviceStatus;
    private Switch skipAds;
    private Switch skipImages;
    private Switch skipLives;
    private Switch skipVideos;
    private EditText keywordInput;
    private Button saveKeywords;
    private SharedPreferences preferences;
    private boolean loading = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildContent());
        setControlsEnabled(false);
    }

    @Override
    protected void onStart() {
        super.onStart();
        ModuleApplication.addServiceStateListener(this, true);
    }

    @Override
    protected void onStop() {
        ModuleApplication.removeServiceStateListener(this);
        super.onStop();
    }

    @Override
    public void onServiceStateChanged(XposedService service) {
        runOnUiThread(() -> bindPreferences(service));
    }

    private View buildContent() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BACKGROUND);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(32), dp(20), dp(32));
        scroll.addView(root, matchWrap());

        TextView title = text("抖仙人", 28, TEXT_PRIMARY);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        root.addView(title, matchWrap());

        TextView subtitle = text("内容跳过设置", 15, PRIMARY);
        LinearLayout.LayoutParams subtitleParams = matchWrap();
        subtitleParams.topMargin = dp(4);
        root.addView(subtitle, subtitleParams);

        serviceStatus = text("正在连接 LSPosed 服务…", 13, TEXT_SECONDARY);
        LinearLayout.LayoutParams statusParams = matchWrap();
        statusParams.topMargin = dp(16);
        root.addView(serviceStatus, statusParams);

        TextView typeHeader = sectionTitle("跳过类型");
        LinearLayout.LayoutParams typeHeaderParams = matchWrap();
        typeHeaderParams.topMargin = dp(28);
        root.addView(typeHeader, typeHeaderParams);

        LinearLayout typeCard = card();
        skipAds = addSwitch(
                typeCard,
                "广告",
                "跳过当前模型明确标记的广告",
                FilterPreferences.KEY_SKIP_ADS
        );
        addDivider(typeCard);
        skipImages = addSwitch(
                typeCard,
                "图文",
                "跳过图集、长文章及其他非视频内容",
                FilterPreferences.KEY_SKIP_IMAGES
        );
        addDivider(typeCard);
        skipLives = addSwitch(
                typeCard,
                "直播",
                "跳过直播间内容",
                FilterPreferences.KEY_SKIP_LIVES
        );
        addDivider(typeCard);
        skipVideos = addSwitch(
                typeCard,
                "视频",
                "跳过全部普通视频；关闭时仍应用关键词过滤",
                FilterPreferences.KEY_SKIP_VIDEOS
        );
        LinearLayout.LayoutParams typeCardParams = matchWrap();
        typeCardParams.topMargin = dp(10);
        root.addView(typeCard, typeCardParams);

        TextView keywordHeader = sectionTitle("视频关键词");
        LinearLayout.LayoutParams keywordHeaderParams = matchWrap();
        keywordHeaderParams.topMargin = dp(28);
        root.addView(keywordHeader, keywordHeaderParams);

        LinearLayout keywordCard = card();
        TextView keywordHint = text(
                "普通视频的标题或介绍包含任意关键词时自动跳过。忽略英文大小写，支持换行、逗号或分号分隔。",
                14,
                TEXT_SECONDARY
        );
        keywordHint.setLineSpacing(0, 1.25f);
        keywordCard.addView(keywordHint, matchWrap());

        keywordInput = new EditText(this);
        keywordInput.setTextColor(TEXT_PRIMARY);
        keywordInput.setHintTextColor(Color.rgb(112, 114, 123));
        keywordInput.setTextSize(16);
        keywordInput.setHint("例如：游戏推广\n不感兴趣\n带货");
        keywordInput.setGravity(Gravity.TOP | Gravity.START);
        keywordInput.setMinLines(4);
        keywordInput.setMaxLines(8);
        keywordInput.setInputType(
                InputType.TYPE_CLASS_TEXT
                        | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                        | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        );
        GradientDrawable inputBackground = rounded(Color.rgb(38, 39, 45), 12);
        inputBackground.setStroke(dp(1), Color.rgb(58, 60, 68));
        keywordInput.setBackground(inputBackground);
        keywordInput.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams inputParams = matchWrap();
        inputParams.topMargin = dp(14);
        keywordCard.addView(keywordInput, inputParams);

        saveKeywords = new Button(this);
        saveKeywords.setText("保存关键词");
        saveKeywords.setTextColor(Color.WHITE);
        saveKeywords.setTextSize(15);
        saveKeywords.setAllCaps(false);
        saveKeywords.setBackground(rounded(PRIMARY, 12));
        saveKeywords.setOnClickListener(view -> saveKeywordSettings());
        LinearLayout.LayoutParams buttonParams = matchWrap();
        buttonParams.topMargin = dp(14);
        buttonParams.height = dp(48);
        keywordCard.addView(saveKeywords, buttonParams);

        LinearLayout.LayoutParams keywordCardParams = matchWrap();
        keywordCardParams.topMargin = dp(10);
        root.addView(keywordCard, keywordCardParams);

        TextView footer = text(
                "设置保存后会同步给抖音进程。若抖音已在后台运行但未立即生效，请强制停止后重新打开。",
                13,
                TEXT_SECONDARY
        );
        footer.setLineSpacing(0, 1.25f);
        LinearLayout.LayoutParams footerParams = matchWrap();
        footerParams.topMargin = dp(20);
        root.addView(footer, footerParams);
        return scroll;
    }

    private void bindPreferences(XposedService service) {
        loading = true;
        if (service == null) {
            preferences = null;
            serviceStatus.setText("未连接 LSPosed 服务，设置暂不可修改");
            serviceStatus.setTextColor(Color.rgb(255, 166, 77));
            setControlsEnabled(false);
            loading = false;
            return;
        }

        preferences = service.getRemotePreferences(FilterPreferences.NAME);
        FilterPreferences.Values values = FilterPreferences.read(preferences);
        skipAds.setChecked(values.skipAds);
        skipImages.setChecked(values.skipImages);
        skipLives.setChecked(values.skipLives);
        skipVideos.setChecked(values.skipVideos);
        keywordInput.setText(values.keywordText);
        keywordInput.setSelection(keywordInput.length());
        serviceStatus.setText(
                "已连接 " + service.getFrameworkName()
                        + " · API " + service.getApiVersion()
        );
        serviceStatus.setTextColor(Color.rgb(84, 214, 142));
        setControlsEnabled(true);
        loading = false;
    }

    private Switch addSwitch(
            LinearLayout parent,
            String label,
            String description,
            String preferenceKey
    ) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(14), dp(12), dp(14));

        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        TextView labelView = text(label, 17, TEXT_PRIMARY);
        labelView.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        copy.addView(labelView, matchWrap());
        TextView descriptionView = text(description, 13, TEXT_SECONDARY);
        descriptionView.setLineSpacing(0, 1.15f);
        LinearLayout.LayoutParams descriptionParams = matchWrap();
        descriptionParams.topMargin = dp(3);
        copy.addView(descriptionView, descriptionParams);
        row.addView(copy, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Switch toggle = new Switch(this);
        toggle.setShowText(false);
        toggle.setButtonTintList(null);
        toggle.setOnCheckedChangeListener((button, checked) -> {
            if (!loading) {
                saveBoolean(preferenceKey, checked);
            }
        });
        row.addView(toggle, wrapWrap());
        parent.addView(row, matchWrap());
        return toggle;
    }

    private void saveBoolean(String key, boolean value) {
        SharedPreferences current = preferences;
        if (current == null) {
            return;
        }
        SharedPreferences.Editor editor = current.edit();
        if (editor != null) {
            editor.putBoolean(key, value).apply();
        }
    }

    private void saveKeywordSettings() {
        SharedPreferences current = preferences;
        if (current == null) {
            Toast.makeText(this, "未连接 LSPosed 服务", Toast.LENGTH_SHORT).show();
            return;
        }
        SharedPreferences.Editor editor = current.edit();
        if (editor == null) {
            Toast.makeText(this, "设置保存失败", Toast.LENGTH_SHORT).show();
            return;
        }
        editor.putString(
                FilterPreferences.KEY_VIDEO_KEYWORDS,
                keywordInput.getText().toString().trim()
        ).apply();
        Toast.makeText(this, "关键词已保存", Toast.LENGTH_SHORT).show();
    }

    private void setControlsEnabled(boolean enabled) {
        skipAds.setEnabled(enabled);
        skipImages.setEnabled(enabled);
        skipLives.setEnabled(enabled);
        skipVideos.setEnabled(enabled);
        keywordInput.setEnabled(enabled);
        saveKeywords.setEnabled(enabled);
        saveKeywords.setAlpha(enabled ? 1f : 0.45f);
    }

    private LinearLayout card() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(2), dp(2), dp(2), dp(2));
        layout.setBackground(rounded(CARD, 16));
        return layout;
    }

    private void addDivider(LinearLayout parent) {
        View divider = new View(this);
        divider.setBackgroundColor(Color.rgb(47, 48, 55));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(1)
        );
        params.leftMargin = dp(16);
        params.rightMargin = dp(16);
        parent.addView(divider, params);
    }

    private TextView sectionTitle(String value) {
        TextView view = text(value, 18, TEXT_PRIMARY);
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private TextView text(CharSequence value, float size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
    }

    private static LinearLayout.LayoutParams wrapWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
