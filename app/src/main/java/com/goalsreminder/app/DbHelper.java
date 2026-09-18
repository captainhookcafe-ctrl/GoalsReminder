package com.goalsreminder.app;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public class DbHelper extends SQLiteOpenHelper {
    private static final String DB_NAME = "goals_reminder.db";
    private static final int DB_VERSION = 1;

    public DbHelper(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE tasks (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "title TEXT NOT NULL," +
                "hour INTEGER NOT NULL," +
                "minute INTEGER NOT NULL," +
                "day_mask INTEGER NOT NULL," +
                "active INTEGER NOT NULL DEFAULT 1," +
                "created_at INTEGER NOT NULL)");

        db.execSQL("CREATE TABLE day_tasks (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "task_id INTEGER NOT NULL," +
                "date TEXT NOT NULL," +
                "title_snapshot TEXT NOT NULL," +
                "hour INTEGER NOT NULL," +
                "minute INTEGER NOT NULL," +
                "completed INTEGER NOT NULL DEFAULT 0," +
                "completed_at INTEGER," +
                "UNIQUE(task_id, date))");

        db.execSQL("CREATE TABLE notes (" +
                "date TEXT NOT NULL," +
                "period INTEGER NOT NULL," +
                "g1a TEXT NOT NULL DEFAULT ''," +
                "g1b TEXT NOT NULL DEFAULT ''," +
                "g1c TEXT NOT NULL DEFAULT ''," +
                "g2a TEXT NOT NULL DEFAULT ''," +
                "g2b TEXT NOT NULL DEFAULT ''," +
                "g2c TEXT NOT NULL DEFAULT ''," +
                "positive TEXT NOT NULL DEFAULT ''," +
                "good TEXT NOT NULL DEFAULT ''," +
                "learned TEXT NOT NULL DEFAULT ''," +
                "updated_at INTEGER NOT NULL," +
                "PRIMARY KEY(date, period))");

        db.execSQL("CREATE INDEX idx_day_tasks_date ON day_tasks(date)");
        db.execSQL("CREATE INDEX idx_day_tasks_task ON day_tasks(task_id)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Future versions must preserve user data here.
    }

    public long addTask(String title, int hour, int minute, int dayMask) {
        ContentValues v = new ContentValues();
        v.put("title", title);
        v.put("hour", hour);
        v.put("minute", minute);
        v.put("day_mask", dayMask);
        v.put("active", 1);
        v.put("created_at", System.currentTimeMillis());
        long id = getWritableDatabase().insertOrThrow("tasks", null, v);
        syncTodayTask(id);
        return id;
    }

    public void updateTask(long id, String title, int hour, int minute, int dayMask) {
        ContentValues v = new ContentValues();
        v.put("title", title);
        v.put("hour", hour);
        v.put("minute", minute);
        v.put("day_mask", dayMask);
        getWritableDatabase().update("tasks", v, "id=?", new String[]{String.valueOf(id)});
        syncTodayTask(id);
    }

    public void deleteTask(long id) {
        ContentValues v = new ContentValues();
        v.put("active", 0);
        getWritableDatabase().update("tasks", v, "id=?", new String[]{String.valueOf(id)});
        getWritableDatabase().delete("day_tasks", "task_id=? AND date=?", new String[]{String.valueOf(id), LocalDate.now().toString()});
    }

    public TaskItem getTask(long id) {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT id,title,hour,minute,day_mask,active FROM tasks WHERE id=?",
                new String[]{String.valueOf(id)})) {
            if (!c.moveToFirst()) return null;
            return taskFromCursor(c);
        }
    }

    public List<TaskItem> getActiveTasks() {
        List<TaskItem> result = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT id,title,hour,minute,day_mask,active FROM tasks WHERE active=1 ORDER BY hour,minute,id", null)) {
            while (c.moveToNext()) result.add(taskFromCursor(c));
        }
        return result;
    }

    private TaskItem taskFromCursor(Cursor c) {
        TaskItem t = new TaskItem();
        t.id = c.getLong(0);
        t.title = c.getString(1);
        t.hour = c.getInt(2);
        t.minute = c.getInt(3);
        t.dayMask = c.getInt(4);
        t.active = c.getInt(5) == 1;
        return t;
    }

    public static int calendarDay(LocalDate date) {
        switch (date.getDayOfWeek()) {
            case SUNDAY: return 1;
            case MONDAY: return 2;
            case TUESDAY: return 3;
            case WEDNESDAY: return 4;
            case THURSDAY: return 5;
            case FRIDAY: return 6;
            default: return 7;
        }
    }

    public static boolean scheduledOn(TaskItem task, LocalDate date) {
        int d = calendarDay(date);
        return (task.dayMask & (1 << d)) != 0;
    }

    public void ensureDay(LocalDate date) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            int dow = calendarDay(date);
            String ds = date.toString();
            try (Cursor c = db.rawQuery(
                    "SELECT id,title,hour,minute,day_mask FROM tasks WHERE active=1", null)) {
                while (c.moveToNext()) {
                    int mask = c.getInt(4);
                    if ((mask & (1 << dow)) == 0) continue;
                    ContentValues v = new ContentValues();
                    v.put("task_id", c.getLong(0));
                    v.put("date", ds);
                    v.put("title_snapshot", c.getString(1));
                    v.put("hour", c.getInt(2));
                    v.put("minute", c.getInt(3));
                    db.insertWithOnConflict("day_tasks", null, v, SQLiteDatabase.CONFLICT_IGNORE);
                }
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    private void syncTodayTask(long taskId) {
        LocalDate today = LocalDate.now();
        TaskItem t = getTask(taskId);
        if (t == null || !t.active || !scheduledOn(t, today)) {
            getWritableDatabase().delete("day_tasks", "task_id=? AND date=?", new String[]{String.valueOf(taskId), today.toString()});
            return;
        }
        ContentValues v = new ContentValues();
        v.put("task_id", t.id);
        v.put("date", today.toString());
        v.put("title_snapshot", t.title);
        v.put("hour", t.hour);
        v.put("minute", t.minute);
        long row = getWritableDatabase().insertWithOnConflict("day_tasks", null, v, SQLiteDatabase.CONFLICT_IGNORE);
        if (row == -1) {
            ContentValues update = new ContentValues();
            update.put("title_snapshot", t.title);
            update.put("hour", t.hour);
            update.put("minute", t.minute);
            getWritableDatabase().update("day_tasks", update, "task_id=? AND date=?",
                    new String[]{String.valueOf(taskId), today.toString()});
        }
    }

    public List<DayTask> getDayTasks(LocalDate date) {
        ensureDay(date);
        List<DayTask> result = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT id,task_id,date,title_snapshot,hour,minute,completed FROM day_tasks WHERE date=? " +
                        "ORDER BY completed ASC,hour ASC,minute ASC,id ASC",
                new String[]{date.toString()})) {
            while (c.moveToNext()) {
                DayTask d = new DayTask();
                d.id = c.getLong(0);
                d.taskId = c.getLong(1);
                d.date = c.getString(2);
                d.title = c.getString(3);
                d.hour = c.getInt(4);
                d.minute = c.getInt(5);
                d.completed = c.getInt(6) == 1;
                result.add(d);
            }
        }
        return result;
    }

    public boolean isCompleted(long taskId, LocalDate date) {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT completed FROM day_tasks WHERE task_id=? AND date=?",
                new String[]{String.valueOf(taskId), date.toString()})) {
            return c.moveToFirst() && c.getInt(0) == 1;
        }
    }

    public void setCompleted(long taskId, LocalDate date, boolean completed) {
        ensureDay(date);
        ContentValues v = new ContentValues();
        v.put("completed", completed ? 1 : 0);
        if (completed) v.put("completed_at", System.currentTimeMillis());
        else v.putNull("completed_at");
        getWritableDatabase().update("day_tasks", v, "task_id=? AND date=?",
                new String[]{String.valueOf(taskId), date.toString()});
    }

    public NoteRecord getNote(LocalDate date, int period) {
        NoteRecord n = new NoteRecord();
        n.date = date.toString();
        n.period = period;
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT g1a,g1b,g1c,g2a,g2b,g2c,positive,good,learned FROM notes WHERE date=? AND period=?",
                new String[]{n.date, String.valueOf(period)})) {
            if (c.moveToFirst()) {
                for (int i = 0; i < 9; i++) n.values[i] = c.getString(i) == null ? "" : c.getString(i);
            }
        }
        return n;
    }

    public void saveNoteField(LocalDate date, int period, int index, String text) {
        String[] columns = {"g1a","g1b","g1c","g2a","g2b","g2c","positive","good","learned"};
        if (index < 0 || index >= columns.length) return;
        ContentValues seed = new ContentValues();
        seed.put("date", date.toString());
        seed.put("period", period);
        seed.put("updated_at", System.currentTimeMillis());
        getWritableDatabase().insertWithOnConflict("notes", null, seed, SQLiteDatabase.CONFLICT_IGNORE);

        ContentValues v = new ContentValues();
        v.put(columns[index], text == null ? "" : text);
        v.put("updated_at", System.currentTimeMillis());
        getWritableDatabase().update("notes", v, "date=? AND period=?", new String[]{date.toString(), String.valueOf(period)});
    }

    public List<String> getHistoryDates() {
        List<String> out = new ArrayList<>();
        String sql = "SELECT date FROM (SELECT date FROM day_tasks UNION SELECT date FROM notes) ORDER BY date DESC";
        try (Cursor c = getReadableDatabase().rawQuery(sql, null)) {
            while (c.moveToNext()) out.add(c.getString(0));
        }
        return out;
    }

    public int[] getDayCounts(String date) {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*), COALESCE(SUM(completed),0) FROM day_tasks WHERE date=?", new String[]{date})) {
            if (c.moveToFirst()) return new int[]{c.getInt(0), c.getInt(1)};
        }
        return new int[]{0,0};
    }

    public List<AnalysisRow> analyze(LocalDate from, LocalDate to) {
        List<AnalysisRow> rows = new ArrayList<>();
        String sql = "SELECT task_id, MAX(title_snapshot), COUNT(*), COALESCE(SUM(completed),0) " +
                "FROM day_tasks WHERE date BETWEEN ? AND ? GROUP BY task_id";
        try (Cursor c = getReadableDatabase().rawQuery(sql, new String[]{from.toString(), to.toString()})) {
            while (c.moveToNext()) {
                AnalysisRow r = new AnalysisRow();
                r.taskId = c.getLong(0);
                r.title = c.getString(1);
                r.planned = c.getInt(2);
                r.completed = c.getInt(3);
                rows.add(r);
            }
        }
        rows.sort((a,b) -> Double.compare(b.rate(), a.rate()));
        return rows;
    }

    public int[] analyzeTotal(LocalDate from, LocalDate to) {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*), COALESCE(SUM(completed),0) FROM day_tasks WHERE date BETWEEN ? AND ?",
                new String[]{from.toString(), to.toString()})) {
            if (c.moveToFirst()) return new int[]{c.getInt(0), c.getInt(1)};
        }
        return new int[]{0,0};
    }

    public static class AnalysisRow {
        public long taskId;
        public String title;
        public int planned;
        public int completed;
        public double rate() { return planned == 0 ? 0 : (completed * 100.0 / planned); }
    }
}
