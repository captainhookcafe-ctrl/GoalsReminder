package com.goalsreminder.app;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

public final class AlarmScheduler {
    private AlarmScheduler() {}

    private static PendingIntent taskIntent(Context context, long taskId, String kind, int flags) {
        Intent intent = new Intent(context, ReminderReceiver.class);
        intent.putExtra("task_id", taskId);
        intent.putExtra("kind", kind);
        long raw = taskId * 2L + ("end".equals(kind) ? 1L : 0L);
        int requestCode = (int)(raw & 0x7fffffff);
        return PendingIntent.getBroadcast(context, requestCode, intent, flags | PendingIntent.FLAG_IMMUTABLE);
    }

    public static void cancelTask(Context context, long taskId) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        for (String kind : new String[]{"start", "end"}) {
            PendingIntent pi = taskIntent(context, taskId, kind, PendingIntent.FLAG_NO_CREATE);
            if (pi != null) {
                am.cancel(pi);
                pi.cancel();
            }
        }
    }

    public static void scheduleTask(Context context, long taskId) {
        DbHelper db = new DbHelper(context.getApplicationContext());
        TaskItem task = db.getTask(taskId);
        cancelTask(context, taskId);
        if (task == null || !task.active) return;

        LocalDateTime now = LocalDateTime.now();
        for (int add = 0; add <= 8; add++) {
            LocalDate date = now.toLocalDate().plusDays(add);
            if (!DbHelper.scheduledOn(task, date)) continue;
            if (add == 0 && db.isCompleted(task.id, date)) continue;

            LocalDateTime start = date.atTime(task.hour, task.minute);
            LocalTime endTime = effectiveEnd(task);
            LocalDateTime end = date.atTime(endTime);

            if (!end.isAfter(start)) continue;
            if (!end.isAfter(now)) continue;

            if (start.isAfter(now)) scheduleOne(context, task.id, "start", start);
            scheduleOne(context, task.id, "end", end);
            return;
        }
    }

    private static LocalTime effectiveEnd(TaskItem task) {
        if (task.endHour >= 0 && task.endMinute >= 0) {
            return LocalTime.of(task.endHour, task.endMinute);
        }
        int total = Math.min(1439, task.hour * 60 + task.minute + 30);
        return LocalTime.of(total / 60, total % 60);
    }

    private static void scheduleOne(Context context, long taskId, String kind, LocalDateTime time) {
        long trigger = time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        PendingIntent pi = taskIntent(context, taskId, kind, PendingIntent.FLAG_UPDATE_CURRENT);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi);
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi);
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, trigger, pi);
            }
        } catch (SecurityException ex) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi);
        }
    }

    public static void scheduleAll(Context context) {
        DbHelper db = new DbHelper(context.getApplicationContext());
        List<TaskItem> tasks = db.getActiveTasks();
        for (TaskItem task : tasks) scheduleTask(context, task.id);
        scheduleMaintenance(context);
    }

    public static void scheduleMaintenance(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        Intent i = new Intent(context, MaintenanceReceiver.class);
        PendingIntent pi = PendingIntent.getBroadcast(context, 2147483001, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        LocalDateTime next = LocalDate.now().plusDays(1).atTime(0, 1);
        long trigger = next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi);
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi);
            }
        } catch (SecurityException ex) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi);
        }
    }
}
