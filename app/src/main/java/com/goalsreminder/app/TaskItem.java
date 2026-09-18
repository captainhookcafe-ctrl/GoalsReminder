package com.goalsreminder.app;

public class TaskItem {
    public long id;
    public String title;
    public int hour;
    public int minute;
    public int endHour;
    public int endMinute;
    public int dayMask;
    public boolean active;

    public int startMinutes() {
        return hour * 60 + minute;
    }

    public int endMinutes() {
        if (endHour < 0 || endMinute < 0) return Math.min(1439, startMinutes() + 30);
        return endHour * 60 + endMinute;
    }
}
