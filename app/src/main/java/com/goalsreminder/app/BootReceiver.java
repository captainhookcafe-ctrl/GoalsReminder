package com.goalsreminder.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import java.time.LocalDate;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        new DbHelper(context).ensureDay(LocalDate.now());
        AlarmScheduler.scheduleAll(context);
    }
}
