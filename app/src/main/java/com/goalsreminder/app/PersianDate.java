package com.goalsreminder.app;

import java.time.LocalDate;

public final class PersianDate {
    private PersianDate() {}

    private static final String[] MONTHS = {
            "فروردین", "اردیبهشت", "خرداد", "تیر", "مرداد", "شهریور",
            "مهر", "آبان", "آذر", "دی", "بهمن", "اسفند"
    };

    public static int[] fromGregorian(LocalDate date) {
        return gregorianToJalali(date.getYear(), date.getMonthValue(), date.getDayOfMonth());
    }

    public static LocalDate toGregorian(int jy, int jm, int jd) {
        int[] g = jalaliToGregorian(jy, jm, jd);
        return LocalDate.of(g[0], g[1], g[2]);
    }

    public static String format(LocalDate date) {
        int[] j = fromGregorian(date);
        return toPersianDigits(j[2] + " " + MONTHS[j[1] - 1] + " " + j[0]);
    }

    public static String formatWithWeekday(LocalDate date) {
        String weekday;
        switch (date.getDayOfWeek()) {
            case SATURDAY: weekday = "شنبه"; break;
            case SUNDAY: weekday = "یکشنبه"; break;
            case MONDAY: weekday = "دوشنبه"; break;
            case TUESDAY: weekday = "سه‌شنبه"; break;
            case WEDNESDAY: weekday = "چهارشنبه"; break;
            case THURSDAY: weekday = "پنجشنبه"; break;
            default: weekday = "جمعه";
        }
        return weekday + "، " + format(date);
    }

    public static String toPersianDigits(String input) {
        if (input == null) return "";
        char[] en = {'0','1','2','3','4','5','6','7','8','9'};
        char[] fa = {'۰','۱','۲','۳','۴','۵','۶','۷','۸','۹'};
        String out = input;
        for (int i = 0; i < 10; i++) out = out.replace(en[i], fa[i]);
        return out;
    }

    public static int[] gregorianToJalali(int gy, int gm, int gd) {
        int[] gdm = {0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334};
        int jy;
        if (gy > 1600) {
            jy = 979;
            gy -= 1600;
        } else {
            jy = 0;
            gy -= 621;
        }
        int gy2 = gm > 2 ? gy + 1 : gy;
        int days = 365 * gy + (gy2 + 3) / 4 - (gy2 + 99) / 100 + (gy2 + 399) / 400 - 80 + gd + gdm[gm - 1];
        jy += 33 * (days / 12053);
        days %= 12053;
        jy += 4 * (days / 1461);
        days %= 1461;
        if (days > 365) {
            jy += (days - 1) / 365;
            days = (days - 1) % 365;
        }
        int jm, jd;
        if (days < 186) {
            jm = 1 + days / 31;
            jd = 1 + days % 31;
        } else {
            jm = 7 + (days - 186) / 30;
            jd = 1 + (days - 186) % 30;
        }
        return new int[]{jy, jm, jd};
    }

    public static int[] jalaliToGregorian(int jy, int jm, int jd) {
        jy += 1595;
        int days = -355668 + 365 * jy + (jy / 33) * 8 + ((jy % 33 + 3) / 4) + jd;
        if (jm < 7) days += (jm - 1) * 31;
        else days += (jm - 7) * 30 + 186;

        int gy = 400 * (days / 146097);
        days %= 146097;
        if (days > 36524) {
            gy += 100 * (--days / 36524);
            days %= 36524;
            if (days >= 365) days++;
        }
        gy += 4 * (days / 1461);
        days %= 1461;
        if (days > 365) {
            gy += (days - 1) / 365;
            days = (days - 1) % 365;
        }
        int gd = days + 1;
        int[] salA = {0,31,28,31,30,31,30,31,31,30,31,30,31};
        boolean leap = (gy % 4 == 0 && gy % 100 != 0) || (gy % 400 == 0);
        if (leap) salA[2] = 29;
        int gm = 1;
        while (gm <= 12 && gd > salA[gm]) {
            gd -= salA[gm];
            gm++;
        }
        return new int[]{gy, gm, gd};
    }
}
