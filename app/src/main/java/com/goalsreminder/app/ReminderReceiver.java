package com.goalsreminder.app;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

import java.time.LocalDate;

public class ReminderReceiver extends BroadcastReceiver {
    private static final String CHANNEL_ID = "goals_reminder_tasks";

    @Override
    public void onReceive(Context context, Intent intent) {
        long taskId = intent.getLongExtra("task_id", -1);
        String kind = intent.getStringExtra("kind");
        if (taskId < 0) return;
        if (kind == null) kind = "start";

        DbHelper db = new DbHelper(context);
        TaskItem task = db.getTask(taskId);
        LocalDate today = LocalDate.now();
        db.ensureDay(today);

        if (task != null && task.active && DbHelper.scheduledOn(task, today) && !db.isCompleted(taskId, today)) {
            showNotification(context, task, kind);
        }

        if ("end".equals(kind)) {
            AlarmScheduler.scheduleTask(context, taskId);
        }
    }

    private void showNotification(Context context, TaskItem task, String kind) {
        boolean english = "en".equals(
                context.getSharedPreferences("goals_settings", Context.MODE_PRIVATE)
                        .getString("language", "fa"));

        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    english ? "Task reminders" : "یادآوری کارها",
                    NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription(english
                    ? "Start and end reminders for scheduled tasks"
                    : "یادآوری شروع و پایان کارهای برنامه‌ریزی‌شده");
            nm.createNotificationChannel(channel);
        }

        if (Build.VERSION.SDK_INT >= 33 &&
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        Intent open = new Intent(context, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(
                context,
                9000 + (int)(task.id & 0xffff),
                open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        boolean ending = "end".equals(kind);
        String title = ending
                ? (english ? "Time is up" : "زمان کار تمام شد")
                : (english ? "Task started" : "کار شروع شد");
        String text = task.title;

        android.app.Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new android.app.Notification.Builder(context, CHANNEL_ID)
                : new android.app.Notification.Builder(context);

        b.setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(contentIntent)
                .setAutoCancel(true)
                .setPriority(android.app.Notification.PRIORITY_HIGH);

        int id = (int)((task.id * 2L + (ending ? 1L : 0L)) & 0x7fffffff);
        nm.notify(id, b.build());
    }
}
