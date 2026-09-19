package com.primemakers.primemusic;

import org.json.JSONException;
import org.json.JSONObject;

public final class Track {
    public final String id;
    public final String title;
    public final String artist;
    public final String source;
    public final String artUri;
    public final long durationMs;
    public final boolean isDefault;
    public final boolean isPicked;

    public Track(String id, String title, String artist, String source, String artUri,
                 long durationMs, boolean isDefault, boolean isPicked) {
        this.id = id;
        this.title = title == null || title.isBlank() ? "Unknown Track" : title;
        this.artist = artist == null || artist.isBlank() || "<unknown>".equalsIgnoreCase(artist)
                ? "Unknown Artist" : artist;
        this.source = source;
        this.artUri = artUri == null ? "" : artUri;
        this.durationMs = Math.max(0, durationMs);
        this.isDefault = isDefault;
        this.isPicked = isPicked;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("title", title);
        o.put("artist", artist);
        o.put("source", source);
        o.put("artUri", artUri);
        o.put("duration", durationMs);
        o.put("default", isDefault);
        o.put("picked", isPicked);
        return o;
    }
}