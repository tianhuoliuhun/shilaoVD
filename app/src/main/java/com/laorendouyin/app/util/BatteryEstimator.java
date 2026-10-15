package com.laorendouyin.app.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 电量显示 + 预计可播放时长估算。
 *
 * 纯 Java、不依赖 Android，方便在 JVM 上单测（真正读系统电量的代码在 FeedActivity 里）。
 *
 * 估算思路：前台期间记录「电量从哪个百分比、在什么时刻到达」，用最近一段时间的
 * 平均掉电速度（每小时掉多少个百分点）反推还能播多久。
 * 数据不够（刚启动、刚拔插头、掉电太慢测不出来）时，用一个保守的默认速度兜底。
 *
 * 注意：只在 App 前台时采样，所以后台期间的掉电不计入；这是有意为之，
 * 因为我们要回答的是「在这个 App 里还能看多久」。
 */
public final class BatteryEstimator {

    /** 只看最近这么久的掉电，避免早上的数据影响下午的判断 */
    static final long WINDOW_MS = 30 * 60 * 1000L;
    /** 跨度不足这么久，测出的速度不可信，走兜底 */
    static final long MIN_SPAN_MS = 5 * 60 * 1000L;
    /** 没有可信测量时的兜底速度：播放视频约每小时掉 12% */
    static final double DEFAULT_PERCENT_PER_HOUR = 12.0;
    /** 测量结果的合理范围，超出说明数据有噪声，同样走兜底 */
    static final double MIN_RATE = 2.0;
    static final double MAX_RATE = 60.0;

    /** 采样：{elapsedMs, percent}，只在电量变化时新增一条（记的是「首次到达该百分比的时刻」） */
    private final List<long[]> mSamples = new ArrayList<>();

    /**
     * 喂入一次电量读数，返回信息栏要显示的整行文字。
     *
     * @param nowMs    单调时钟毫秒（SystemClock.elapsedRealtime）
     * @param percent  当前电量 0~100
     * @param charging 是否在充电（含已充满仍接着插着的情况）
     */
    public String update(long nowMs, int percent, boolean charging) {
        if (charging) {
            // 充电期间的数据没有意义，拔掉后从头测
            mSamples.clear();
            return "电量 " + percent + "%\n充电中";
        }

        // 电量比上次采样还高 => 中间插过电，之前的样本作废
        if (!mSamples.isEmpty() && percent > last()[1]) {
            mSamples.clear();
        }
        // 只在电量真的下降时才新增样本；同一百分比保持「首次到达」的时刻不变
        if (mSamples.isEmpty() || percent < last()[1]) {
            mSamples.add(new long[]{nowMs, percent});
        }
        prune(nowMs);

        double rate = measuredRatePerHour(nowMs, percent);
        return format(percent, minutesLeft(percent, rate));
    }

    /** 丢掉窗口外的旧样本，但保留窗口起点前的最后一条，作为测量的起点 */
    private void prune(long nowMs) {
        while (mSamples.size() >= 2 && mSamples.get(1)[0] <= nowMs - WINDOW_MS) {
            mSamples.remove(0);
        }
    }

    /** 测得的掉电速度（%/小时）；数据不可信时返回兜底值 */
    double measuredRatePerHour(long nowMs, int currentPercent) {
        if (mSamples.isEmpty()) return DEFAULT_PERCENT_PER_HOUR;
        long[] ref = mSamples.get(0);
        long span = nowMs - ref[0];
        int drop = (int) ref[1] - currentPercent;
        if (span < MIN_SPAN_MS || drop < 1) return DEFAULT_PERCENT_PER_HOUR;

        double rate = drop / (span / 3_600_000.0);
        if (rate < MIN_RATE || rate > MAX_RATE) return DEFAULT_PERCENT_PER_HOUR;
        return rate;
    }

    /** 按当前速度反推还能看多少分钟 */
    static int minutesLeft(int percent, double percentPerHour) {
        if (percent <= 0) return 0;
        return (int) Math.round(percent / percentPerHour * 60.0);
    }

    /**
     * 文案。老人看的是一眼能懂的话；**电量与剩余时间分两行**（挤一行会糊成一团）：
     *   电量 78%
     *   约可播 5小时20分
     *   电量 9%
     *   约可播 45分钟
     */
    static String format(int percent, int minutes) {
        if (minutes <= 0) {
            return "电量 " + percent + "%\n电量不足";
        }
        int hours = minutes / 60;
        int mins = minutes % 60;
        String when;
        if (hours == 0) {
            when = mins + "分钟";
        } else if (mins == 0) {
            when = hours + "小时";
        } else {
            when = hours + "小时" + mins + "分";
        }
        return String.format(Locale.US, "电量 %d%%\n约可播 %s", percent, when);
    }

    /**
     * 找出文本里「数字串」的起止区间（连续数字，以及紧跟其后的一个 `%`）。
     *
     * 供 UI 层把数字单独放大用 —— 老人隔着距离看，最需要一眼看清的就是"还剩多少电"。
     * 逻辑放在这个纯 Java 类里，是为了能在 JVM 上单测（真正 setSpan 的代码在 FeedActivity）。
     *
     * @return 每个元素是 {start, end}（左闭右开），按出现顺序；文本为 null 时返回空表
     */
    public static List<int[]> digitSpans(String text) {
        List<int[]> out = new ArrayList<>();
        if (text == null) return out;
        int n = text.length();
        int i = 0;
        while (i < n) {
            if (!Character.isDigit(text.charAt(i))) {
                i++;
                continue;
            }
            int start = i;
            while (i < n && Character.isDigit(text.charAt(i))) i++;
            if (i < n && text.charAt(i) == '%') i++;   // 百分号跟数字一起放大
            out.add(new int[]{start, i});
        }
        return out;
    }

    private long[] last() {
        return mSamples.get(mSamples.size() - 1);
    }
}
