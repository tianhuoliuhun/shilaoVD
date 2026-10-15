package com.laorendouyin.app.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** 电量文案与可播时长估算的回归测试（纯逻辑，不依赖 Android）。 */
public class BatteryEstimatorTest {

    private static final long MIN = 60_000L;

    /** 没有任何采样时走兜底速度：78% / 12%每小时 ≈ 6.5 小时 = 6小时30分 */
    @Test
    public void noSamples_usesDefaultRate() {
        BatteryEstimator b = new BatteryEstimator();
        String s = b.update(0, 78, false);
        assertEquals("电量 78%\n约可播 6小时30分", s);
    }

    /** 充电中只显示「充电中」，不给时长 */
    @Test
    public void charging_showsChargingLabel() {
        BatteryEstimator b = new BatteryEstimator();
        assertEquals("电量 60%\n充电中", b.update(0, 60, true));
    }

    /** 实测速度：30 分钟掉了 10%，即 20%/小时；剩 40% → 2 小时 */
    @Test
    public void measuredRate_isUsed() {
        BatteryEstimator b = new BatteryEstimator();
        b.update(0, 50, false);
        b.update(30 * MIN, 40, false);
        assertEquals("电量 40%\n约可播 2小时", b.update(30 * MIN, 40, false));
    }

    /** 跨度不足 5 分钟，测出来不可信，回退到默认速度 */
    @Test
    public void tooShortSpan_fallsBackToDefault() {
        BatteryEstimator b = new BatteryEstimator();
        b.update(0, 50, false);
        // 2 分钟内掉 5%：不采信，按默认 12%/h 算 45/12h = 3小时45分
        assertEquals("电量 45%\n约可播 3小时45分", b.update(2 * MIN, 45, false));
    }

    /** 电量不足 1 小时时只显示分钟 */
    @Test
    public void lessThanAnHour_showsMinutes() {
        assertEquals("电量 9%\n约可播 45分钟", BatteryEstimator.format(9, 45));
    }

    /** 整点小时不带分钟 */
    @Test
    public void wholeHour_omitsMinutes() {
        assertEquals("电量 50%\n约可播 4小时", BatteryEstimator.format(50, 240));
    }

    /** 0% 时给「电量不足」，不出现负数或 0 分钟的误导文案 */
    @Test
    public void zeroPercent_showsLow() {
        assertEquals("电量 0%\n电量不足", BatteryEstimator.format(0, 0));
    }

    /** 拔掉充电后样本从头测，不会沿用充电前的数据 */
    @Test
    public void afterCharging_samplesReset() {
        BatteryEstimator b = new BatteryEstimator();
        b.update(0, 50, false);
        b.update(10 * MIN, 45, false);
        b.update(20 * MIN, 90, true);            // 插上充电
        String s = b.update(21 * MIN, 90, false); // 拔掉，只有一条新样本 → 兜底
        assertTrue(s, s.startsWith("电量 90%\n约可播"));
        assertEquals("电量 90%\n约可播 7小时30分", s);
    }

    /** 数字区间：电量百分比（含 % 号）与时长里的数字都要被找出来 */
    @Test
    public void digitSpans_findsPercentAndTimeNumbers() {
        String s = "电量 78%\n约可播 5小时20分";
        java.util.List<int[]> r = BatteryEstimator.digitSpans(s);
        assertEquals(3, r.size());
        assertEquals("78%", slice(s, r.get(0)));
        assertEquals("5", slice(s, r.get(1)));
        assertEquals("20", slice(s, r.get(2)));
    }

    /** 只有分钟、充电中、电量不足这几种形态也不能漏或错 */
    @Test
    public void digitSpans_handlesShortForms() {
        String s = "电量 9%\n约可播 45分钟";
        java.util.List<int[]> r = BatteryEstimator.digitSpans(s);
        assertEquals(2, r.size());
        assertEquals("9%", slice(s, r.get(0)));
        assertEquals("45", slice(s, r.get(1)));
        assertEquals(1, BatteryEstimator.digitSpans("电量 60%\n充电中").size());
        assertEquals(1, BatteryEstimator.digitSpans("电量 0%\n电量不足").size());
    }

    /** 区间必须能安全切回原文（左闭右开、不越界），且空/无数字输入不炸 */
    @Test
    public void digitSpans_rangesAreSafe() {
        String s = "电量 100%\n约可播 12小时59分";
        for (int[] r : BatteryEstimator.digitSpans(s)) {
            assertTrue(String.valueOf(r[0]), r[0] >= 0);
            assertTrue(String.valueOf(r[1]), r[1] <= s.length());
            assertTrue("区间必须非空", r[0] < r[1]);
        }
        assertEquals(0, BatteryEstimator.digitSpans("").size());
        assertEquals(0, BatteryEstimator.digitSpans("充电中").size());
        assertEquals(0, BatteryEstimator.digitSpans(null).size());
    }

    private static String slice(String s, int[] r) {
        return s.substring(r[0], r[1]);
    }
}
