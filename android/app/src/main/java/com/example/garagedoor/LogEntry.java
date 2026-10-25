package com.example.garagedoor;

import com.google.gson.annotations.SerializedName;

public class LogEntry {
    @SerializedName("timestamp")
    private String timestamp;

    @SerializedName("event")
    private String event;

    public String getTimestamp() { return timestamp; }
    public String getEvent() { return event; }

    @Override
    public String toString() {
        return timestamp + " — " + event;
    }
}