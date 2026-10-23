package com.example.garagedoor;

import com.google.gson.annotations.SerializedName;

public class DoorStatusResponse {
    @SerializedName("status_code")
    private int statusCode; // 1 - закрыта, 2 - закрывается, 3 - открывается, 4 - открыта[cite: 1]

    @SerializedName("status_text")
    private String statusText;

    @SerializedName("lock_enabled")
    private boolean lockEnabled;

    public int getStatusCode() { return statusCode; }
    public String getStatusText() { return statusText; }
    public boolean isLockEnabled() { return lockEnabled; }
}