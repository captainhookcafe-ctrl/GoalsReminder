package com.goalsreminder.app;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

public final class AlarmScheduler {
    private AlarmScheduler() {}

    private static PendingIntent taskIntent(Context context, long taskId, int flags) {
        Intent intent = new Intent(context, ReminderReceiver.class);
        intent.putExtra("task_id", taskId);
        return PendingIntent.getBroadcast(context, (int)(taskId & 0x7fffffff), intent, flags | PendingIntent.FLAG_IMMUTABLE);
    }

    public static void cancelTask(Context context, long taskId) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        PendingIntent pi = taskIntent(context, taskId, PendingIntent.FLAG_NO_CREATE);
        if (pi != null) {
            am.cancel(pi);
            pi.cancel();
        }
    }

    public static void scheduleTask(Context context, long taskId) {
        DbHelper db = new DbHelper(context.getApplicationContext());
        TaskItem task = db.getTask(taskId);
        cancelTask(context, taskId);
        if (task == null || !task.active) return;

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime next = null;
        for (int add = 0; add <= 8; add++) {
            LocalDate date = now.toLocalDate().plusDays(add);
            if (!DbHelper.scheduledOn(task, date)) continue;
            LocalDateTime candidate = date.atTime(task.hour, task.minute);
            if (!candidate.isAfter(now)) continue;
            if (add == 0 && db.isCompleted(task.id, date)) continue;
            next = candidate;
            break;
        }
        if (next == null) return;

        long trigger = next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        PendingIntent pi = taskIntent(context, taskId, PendingIntent.FLAG_UPDATE_CURRENT);
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
