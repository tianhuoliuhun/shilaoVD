package com.laorendouyin.app.util;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Locale;

/**
 * 顶部信息栏格式的回归测试。
 *
 * 重点是**时间必须强制 24 小时制** —— 顶部栏是给不识字的使用者看的，
 * 12 小时制少一位数字、还得靠上午/下午区分，对老人是纯负担。
 *
 * ⚠️ 这条测试**不能**覆盖"设备开了 12 小时制"这个场景（JVM 里没有 Android 设置），
 * 所以它守的是**实现方式**：只要 `time()` 仍然用 `Calendar.HOUR_OF_DAY` + `%02d`
 * （而不是 `DateFormat`），就天然与系统设置无关。谁哪天把它改成 `DateFormat`，
 * 这里就会红。
 */
public class DateTimeInfoTest {

    /** 时间永远是 24 小时制：两位数字、无 AM/PM、小时落在 0~23 */
    @Test
    public void time_isAlways24Hour() {
        String t = DateTimeInfo.time();
        assertTrue("应形如 19:15，实际=" + t, t.matches("\\d{2}:\\d{2}"));

        int hour = Integer.parseInt(t.substring(0, 2));
        int minute = Integer.parseInt(t.substring(3, 5));
        assertTrue("小时必须在 0~23（24 小时制），实际=" + hour, hour >= 0 && hour <= 23);
        assertTrue("分钟必须在 0~59，实际=" + minute, minute >= 0 && minute <= 59);

        String upper = t.toUpperCase(Locale.US);
        assertFalse("不能出现 AM，实际=" + t, upper.contains("AM"));
        assertFalse("不能出现 PM，实际=" + t, upper.contains("PM"));
        assertFalse("不能出现中文时段字，实际=" + t,
                t.contains("上午") || t.contains("下午"));
    }

    /** 日期格式：10月14日 */
    @Test
    public void date_hasChineseMonthDay() {
        String d = DateTimeInfo.date();
        assertTrue("应形如 10月14日，实际=" + d, d.matches("\\d{1,2}月\\d{1,2}日"));
    }

    /** 星期是「星期X」七个之一 */
    @Test
    public void week_isOneOfSeven() {
        String w = DateTimeInfo.week();
        assertTrue("实际=" + w, w.matches("星期[日一二三四五六]"));
    }
}
