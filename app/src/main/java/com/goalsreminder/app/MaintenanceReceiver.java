package com.goalsreminder.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import java.time.LocalDate;

public class MaintenanceReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        DbHelper db = new DbHelper(context);
        db.ensureDay(LocalDate.now());
        AlarmScheduler.scheduleAll(context);
    }
}
