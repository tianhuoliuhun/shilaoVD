package com.laorendouyin.app.util;

import android.content.Context;

/**
 * 使用须知的同意状态。
 *
 * 在用户同意之前，应用不播放、也不预加载（不暂存视频）。
 * 文案改动时把 {@link #DISCLAIMER_VERSION} 加一，老用户会重新看到弹窗。
 */
public final class LegalState {

    /**
     * 使用须知版本号：改了文案就加一。
     *
     * 历史：
     *   1 = 初版 6 条
     *   2 = 2026-10-19 补第 5 条「接口参数（风控签名 / 来源标识）只用于读取公开内容，
     *       不用于绕过付费墙或权限校验」→ 原 5/6 条顺延为 6/7 条。
     *       理由：a_bogus 这类反爬参数此前在声明里一个字都没提，是第三方最容易挑刺的空白。
     */
    public static final int DISCLAIMER_VERSION = 2;

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
