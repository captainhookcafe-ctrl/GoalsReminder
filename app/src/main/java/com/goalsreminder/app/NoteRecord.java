package com.goalsreminder.app;

public class NoteRecord {
    public String date;
    public int period;
    public String[] values = new String[9];

    public NoteRecord() {
        for (int i = 0; i < values.length; i++) values[i] = "";
    }

    public boolean hasText() {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) return true;
        }
        return false;
    }
}
