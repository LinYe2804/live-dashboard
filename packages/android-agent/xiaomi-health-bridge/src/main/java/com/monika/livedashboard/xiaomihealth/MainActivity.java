package com.monika.livedashboard.xiaomihealth;

import android.app.Activity;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        int padding = (int) (24 * getResources().getDisplayMetrics().density);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER_HORIZONTAL);
        layout.setPadding(padding, padding, padding, padding);

        TextView title = new TextView(this);
        title.setText("小米健康桥接");
        title.setTextSize(24);
        layout.addView(title, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        TextView body = new TextView(this);
        body.setText(
            "安装完成后：\n\n" +
            "1. 打开 LSPosed，启用本模块。\n" +
            "2. 作用域只勾选“小米运动健康”（com.mi.health）。\n" +
            "3. 强制停止并重新打开小米运动健康，或重启手机。\n" +
            "4. 在“实时看板助手”中开启健康数据上报。\n\n" +
            "模块只对实时看板助手放行小米健康的只读数据接口，不会修改健康记录。"
        );
        body.setTextSize(16);
        body.setLineSpacing(0, 1.25f);
        body.setMovementMethod(new ScrollingMovementMethod());
        LinearLayout.LayoutParams bodyParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f
        );
        bodyParams.topMargin = padding;
        layout.addView(body, bodyParams);

        setContentView(layout);
    }
}
