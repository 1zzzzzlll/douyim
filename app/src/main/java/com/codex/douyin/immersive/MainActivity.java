package com.codex.douyin.immersive;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class MainActivity extends Activity {
    private static final int PAD = 24;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(PAD), dp(48), dp(PAD), dp(PAD));
        root.setBackgroundColor(Color.rgb(16, 17, 20));

        TextView title = text("抖仙人", 26, Color.WHITE);
        title.setGravity(Gravity.CENTER);
        root.addView(title, matchWrap());

        TextView api = text("LSPosed Modern API 102", 14, Color.rgb(254, 44, 85));
        api.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams apiParams = matchWrap();
        apiParams.topMargin = dp(8);
        root.addView(api, apiParams);

        SpannableString bodyText = new SpannableString(
                "功能已内置并默认启用\n\n" +
                "• 视频播放时，仅保留视频画面\n" +
                "• 暂停播放时，立即恢复完整界面\n" +
                "• 暂停后可从右侧半透明按钮下载当前无水印视频\n\n" +
                "请在 LSPosed 中启用本模块并勾选“抖音”，然后强制停止并重新打开抖音。"
        );
        bodyText.setSpan(new StyleSpan(android.graphics.Typeface.BOLD),
                0, 9, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        TextView body = text(bodyText, 17, Color.rgb(225, 225, 230));
        body.setLineSpacing(0, 1.35f);
        LinearLayout.LayoutParams bodyParams = matchWrap();
        bodyParams.topMargin = dp(36);
        root.addView(body, bodyParams);

        setContentView(root);
    }

    private TextView text(CharSequence value, float size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
