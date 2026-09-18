package com.goalsreminder.app;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

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

    public JSONObject createBackup(LocalDate from, LocalDate to, boolean full) throws JSONException {
        JSONObject root = new JSONObject();
        root.put("format", "GoalsReminderBackup");
        root.put("version", 1);
        root.put("type", full ? "full" : "range");
        root.put("createdAt", System.currentTimeMillis());
        if (!full) {
            root.put("from", from.toString());
            root.put("to", to.toString());
        }

        JSONArray tasks = new JSONArray();
        String taskSql;
        String[] taskArgs;
        if (full) {
            taskSql = "SELECT id,title,hour,minute,day_mask,active,created_at FROM tasks ORDER BY id";
            taskArgs = null;
        } else {
            taskSql = "SELECT DISTINCT t.id,t.title,t.hour,t.minute,t.day_mask,t.active,t.created_at " +
                    "FROM tasks t JOIN day_tasks d ON d.task_id=t.id WHERE d.date BETWEEN ? AND ? ORDER BY t.id";
            taskArgs = new String[]{from.toString(), to.toString()};
        }
        try (Cursor c = getReadableDatabase().rawQuery(taskSql, taskArgs)) {
            while (c.moveToNext()) {
                JSONObject o = new JSONObject();
                o.put("id", c.getLong(0));
                o.put("title", c.getString(1));
                o.put("hour", c.getInt(2));
                o.put("minute", c.getInt(3));
                o.put("dayMask", c.getInt(4));
                o.put("active", c.getInt(5));
                o.put("createdAt", c.getLong(6));
                tasks.put(o);
            }
        }
        root.put("tasks", tasks);

        JSONArray days = new JSONArray();
        String daySql = full
                ? "SELECT task_id,date,title_snapshot,hour,minute,completed,completed_at FROM day_tasks ORDER BY date,task_id"
                : "SELECT task_id,date,title_snapshot,hour,minute,completed,completed_at FROM day_tasks WHERE date BETWEEN ? AND ? ORDER BY date,task_id";
        String[] dayArgs = full ? null : new String[]{from.toString(), to.toString()};
        try (Cursor c = getReadableDatabase().rawQuery(daySql, dayArgs)) {
            while (c.moveToNext()) {
                JSONObject o = new JSONObject();
                o.put("taskId", c.getLong(0));
                o.put("date", c.getString(1));
                o.put("title", c.getString(2));
                o.put("hour", c.getInt(3));
                o.put("minute", c.getInt(4));
                o.put("completed", c.getInt(5));
                if (!c.isNull(6)) o.put("completedAt", c.getLong(6));
                days.put(o);
            }
        }
        root.put("dayTasks", days);

        JSONArray notes = new JSONArray();
        String noteSql = full
                ? "SELECT date,period,g1a,g1b,g1c,g2a,g2b,g2c,positive,good,learned,updated_at FROM notes ORDER BY date,period"
                : "SELECT date,period,g1a,g1b,g1c,g2a,g2b,g2c,positive,good,learned,updated_at FROM notes WHERE date BETWEEN ? AND ? ORDER BY date,period";
        String[] noteArgs = full ? null : new String[]{from.toString(), to.toString()};
        try (Cursor c = getReadableDatabase().rawQuery(noteSql, noteArgs)) {
            while (c.moveToNext()) {
                JSONObject o = new JSONObject();
                o.put("date", c.getString(0));
                o.put("period", c.getInt(1));
                o.put("g1a", c.getString(2));
                o.put("g1b", c.getString(3));
                o.put("g1c", c.getString(4));
                o.put("g2a", c.getString(5));
                o.put("g2b", c.getString(6));
                o.put("g2c", c.getString(7));
                o.put("positive", c.getString(8));
                o.put("good", c.getString(9));
                o.put("learned", c.getString(10));
                o.put("updatedAt", c.getLong(11));
                notes.put(o);
            }
        }
        root.put("notes", notes);
        return root;
    }

    public String restoreBackup(JSONObject root) throws JSONException {
        if (!"GoalsReminderBackup".equals(root.optString("format"))) {
            throw new JSONException("Invalid backup format");
        }
        String type = root.optString("type", "full");
        boolean full = "full".equals(type);
        JSONArray tasks = root.optJSONArray("tasks");
        JSONArray days = root.optJSONArray("dayTasks");
        JSONArray notes = root.optJSONArray("notes");
        if (tasks == null || days == null || notes == null) throw new JSONException("Incomplete backup");

        SQLiteDatabase db = getWritableDatabase();
        Map<Long, Long> taskMap = new HashMap<>();
        db.beginTransaction();
        try {
            if (full) {
                db.delete("notes", null, null);
                db.delete("day_tasks", null, null);
                db.delete("tasks", null, null);
            }

            for (int i = 0; i < tasks.length(); i++) {
                JSONObject o = tasks.getJSONObject(i);
                long oldId = o.getLong("id");
                long newId;
                if (full) {
                    ContentValues v = new ContentValues();
                    v.put("id", oldId);
                    v.put("title", o.getString("title"));
                    v.put("hour", o.getInt("hour"));
                    v.put("minute", o.getInt("minute"));
                    v.put("day_mask", o.getInt("dayMask"));
                    v.put("active", o.optInt("active", 1));
                    v.put("created_at", o.optLong("createdAt", System.currentTimeMillis()));
                    newId = db.insertWithOnConflict("tasks", null, v, SQLiteDatabase.CONFLICT_REPLACE);
                    if (newId == -1) newId = oldId;
                } else {
                    newId = findMatchingTask(db, o.getString("title"), o.getInt("hour"), o.getInt("minute"), o.getInt("dayMask"));
                    if (newId < 0) {
                        ContentValues v = new ContentValues();
                        v.put("title", o.getString("title"));
                        v.put("hour", o.getInt("hour"));
                        v.put("minute", o.getInt("minute"));
                        v.put("day_mask", o.getInt("dayMask"));
                        v.put("active", 0);
                        v.put("created_at", o.optLong("createdAt", System.currentTimeMillis()));
                        newId = db.insertOrThrow("tasks", null, v);
                    }
                }
                taskMap.put(oldId, newId);
            }

            for (int i = 0; i < days.length(); i++) {
                JSONObject o = days.getJSONObject(i);
                long oldTaskId = o.getLong("taskId");
                Long mapped = taskMap.get(oldTaskId);
                if (mapped == null) continue;
                ContentValues v = new ContentValues();
                v.put("task_id", mapped);
                v.put("date", o.getString("date"));
                v.put("title_snapshot", o.getString("title"));
                v.put("hour", o.getInt("hour"));
                v.put("minute", o.getInt("minute"));
                v.put("completed", o.optInt("completed", 0));
                if (o.has("completedAt")) v.put("completed_at", o.optLong("completedAt"));
                else v.putNull("completed_at");
                db.insertWithOnConflict("day_tasks", null, v, SQLiteDatabase.CONFLICT_REPLACE);
            }

            for (int i = 0; i < notes.length(); i++) {
                JSONObject o = notes.getJSONObject(i);
                ContentValues v = new ContentValues();
                v.put("date", o.getString("date"));
                v.put("period", o.getInt("period"));
                v.put("g1a", o.optString("g1a", ""));
                v.put("g1b", o.optString("g1b", ""));
                v.put("g1c", o.optString("g1c", ""));
                v.put("g2a", o.optString("g2a", ""));
                v.put("g2b", o.optString("g2b", ""));
                v.put("g2c", o.optString("g2c", ""));
                v.put("positive", o.optString("positive", ""));
                v.put("good", o.optString("good", ""));
                v.put("learned", o.optString("learned", ""));
                v.put("updated_at", o.optLong("updatedAt", System.currentTimeMillis()));
                db.insertWithOnConflict("notes", null, v, SQLiteDatabase.CONFLICT_REPLACE);
            }

            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        ensureDay(LocalDate.now());
        return type;
    }

    private long findMatchingTask(SQLiteDatabase db, String title, int hour, int minute, int dayMask) {
        try (Cursor c = db.rawQuery(
                "SELECT id FROM tasks WHERE title=? AND hour=? AND minute=? AND day_mask=? LIMIT 1",
                new String[]{title, String.valueOf(hour), String.valueOf(minute), String.valueOf(dayMask)})) {
            if (c.moveToFirst()) return c.getLong(0);
        }
        return -1;
    }


}
