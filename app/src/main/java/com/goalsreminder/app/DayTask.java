package com.goalsreminder.app;

public class DayTask {
    public long id;
    public long taskId;
    public String date;
    public String title;
    public int hour;
    public int minute;
    public int endHour;
    public int endMinute;
    public boolean completed;

    public int startMinutes() {
        return hour * 60 + minute;
    }

    public int endMinutes() {
        if (endHour < 0 || endMinute < 0) return Math.min(1439, startMinutes() + 30);
        return endHour * 60 + endMinute;
    }
}
