package com.laorendouyin.app.util;

import android.content.Context;

/**
 * 使用须知的同意状态。
 *
 * 在用户同意之前，应用不播放、也不预加载（不暂存视频）。
 * 文案改动时把 {@link #DISCLAIMER_VERSION} 加一，老用户会重新看到弹窗。
 */
public final class LegalState {

    /** 使用须知版本号：改了文案就加一 */
    public static final int DISCLAIMER_VERSION = 1;

    private static final String PREFS = "legal_state";
    private static final String KEY_ACK = "disclaimer_ack";

    private LegalState() {
    }

    public static boolean isDisclaimerAccepted(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_ACK, 0) >= DISCLAIMER_VERSION;
    }

    public static void acceptDisclaimer(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt(KEY_ACK, DISCLAIMER_VERSION).apply();
    }
}
