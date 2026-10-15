package com.laorendouyin.app.net;

import java.util.ArrayList;
import java.util.List;

/**
 * 内容源的轮询 + 失败降级（纯 Java，无 Android 依赖，便于单测）。
 *
 * 规则：
 *   · 每次调用从轮询游标处开始，让多个源的内容自然混合；
 *   · 最近失败过的源进入冷却期（{@link #COOLDOWN_MS}），冷却期内排到后面，优先尝试其它源；
 *   · 单次最多返回 {@link #MAX_ATTEMPTS} 个候选，避免一次刷新里对所有源轮番重试、白耗流量；
 *   · 即使所有源都在冷却中，也照常返回候选 —— 宁可再试一次，也不能一个源都不试。
 *
 * 线程安全：拉流在后台线程执行，所有方法同步。
 */
public final class FeedSourcePicker {

    /** 失败后冷却多久（10 分钟）：足够让接口有时间恢复，又不会长期把好源拒之门外 */
    static final long COOLDOWN_MS = 10 * 60 * 1000L;

    /** 单次刷新最多尝试的源数量 */
    static final int MAX_ATTEMPTS = 3;

    /** 从未失败 / 已恢复 的标记 */
    private static final long NEVER_FAILED = Long.MIN_VALUE;

    private final String[] mSources;
    private final long[] mFailedAt;
    private int mCursor;

    public FeedSourcePicker(String[] sources) {
        mSources = sources.clone();
        mFailedAt = new long[sources.length];
        for (int i = 0; i < mFailedAt.length; i++) mFailedAt[i] = NEVER_FAILED;
    }

    /**
     * 本次刷新应按顺序尝试的源（最多 {@link #MAX_ATTEMPTS} 个）。
     * 调用一次游标前进一格。
     */
    public synchronized List<String> candidates(long nowMs) {
        List<String> out = new ArrayList<>();
        int n = mSources.length;
        if (n == 0) return out;

        int start = mCursor % n;
        mCursor = (mCursor + 1) % n;

        List<String> healthy = new ArrayList<>();
        List<String> cooling = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int idx = (start + i) % n;
            if (isCooling(idx, nowMs)) {
                cooling.add(mSources[idx]);
            } else {
                healthy.add(mSources[idx]);
            }
        }
        out.addAll(healthy);
        out.addAll(cooling);
        return out.size() > MAX_ATTEMPTS ? new ArrayList<>(out.subList(0, MAX_ATTEMPTS)) : out;
    }

    /** 某个源请求失败：记下时间，进入冷却 */
    public synchronized void markFailed(String src, long nowMs) {
        int idx = indexOf(src);
        if (idx >= 0) mFailedAt[idx] = nowMs;
    }

    /** 某个源请求成功：立即解除冷却 */
    public synchronized void markOk(String src) {
        int idx = indexOf(src);
        if (idx >= 0) mFailedAt[idx] = NEVER_FAILED;
    }

    private boolean isCooling(int idx, long nowMs) {
        return mFailedAt[idx] != NEVER_FAILED && nowMs - mFailedAt[idx] < COOLDOWN_MS;
    }

    private int indexOf(String src) {
        for (int i = 0; i < mSources.length; i++) {
            if (mSources[i].equals(src)) return i;
        }
        return -1;
    }
}
