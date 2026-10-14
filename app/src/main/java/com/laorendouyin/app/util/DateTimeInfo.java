package com.laorendouyin.app.util;

import com.nlf.calendar.Lunar;
import com.nlf.calendar.Solar;

import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 顶部信息栏要显示的四样东西：**时间 / 日期 / 星期 / 今日节日**。
 *
 * 关于"今日节日"为什么必须引入农历库：春节、元宵、端午、中秋、重阳这些是老人最在意的节日，
 * 而它们的公历日期每年都在变，靠写死日期表迟早出错 —— 所以用 lunar 库现算。
 * 另外老人的使用场景里，传统节日的权重高于情人节/圣诞节这类，所以**农历节日优先**。
 */
public final class DateTimeInfo {

    private DateTimeInfo() {
    }

    /** 时间：19:15 */
    public static String time() {
        Calendar c = Calendar.getInstance();
        return String.format(Locale.CHINA, "%02d:%02d",
                c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE));
    }

    /** 日期：10月14日 */
    public static String date() {
        Calendar c = Calendar.getInstance();
        return (c.get(Calendar.MONTH) + 1) + "月" + c.get(Calendar.DAY_OF_MONTH) + "日";
    }

    private static final String[] WEEK = {
            "星期日", "星期一", "星期二", "星期三", "星期四", "星期五", "星期六"};

    /** 星期：星期三 */
    public static String week() {
        return WEEK[Calendar.getInstance().get(Calendar.DAY_OF_WEEK) - 1];
    }

    /**
     * 今日节日：没有节日时返回**空串**（调用方据此把那一行藏起来）。
     *
     * 优先级：农历节日（春节/中秋/端午…）> 公历节日（国庆/元旦/劳动节…）。
     * 一天可能有多个节日（比如既是元旦又是某个农历日子），只取排在最前面的那个 ——
     * 老人看的是一眼就懂的短标签，堆一串反而看不清。
     */
    public static String festival() {
        try {
            Solar solar = Solar.fromDate(new Date());
            Lunar lunar = solar.getLunar();

            List<String> lunarFestivals = lunar.getFestivals();
            if (lunarFestivals != null && !lunarFestivals.isEmpty()) {
                return lunarFestivals.get(0);
            }
            List<String> solarFestivals = solar.getFestivals();
            if (solarFestivals != null && !solarFestivals.isEmpty()) {
                return solarFestivals.get(0);
            }
        } catch (Throwable t) {
            // 节日只是锦上添花，算不出来也绝不能连累主流程
            AppLog.i("clock", "节日判断失败: " + t);
        }
        return "";
    }

    /**
     * 农历：九月初九。
     *
     * 闰月要显式带"闰"字（如"闰六月"）—— 老人对农历闰月很敏感，
     * 光写"六月"他们会以为算错了。lunar 库用**负数月份**表示闰月。
     */
    public static String lunar() {
        try {
            Lunar lunar = Solar.fromDate(new Date()).getLunar();
            String month = (lunar.getMonth() < 0 ? "闰" : "") + lunar.getMonthInChinese() + "月";
            return month + lunar.getDayInChinese();
        } catch (Throwable t) {
            AppLog.i("clock", "农历计算失败: " + t);
            return "";
        }
    }
}
