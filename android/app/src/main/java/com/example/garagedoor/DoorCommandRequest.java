package com.example.garagedoor;

import com.google.gson.annotations.SerializedName;

public class DoorCommandRequest {
    @SerializedName("action")
    private String action; // "open" или "close"

    @SerializedName("lock_enabled")
    private boolean lockEnabled;

    public DoorCommandRequest(String action, boolean lockEnabled) {
        this.action = action;
        this.lockEnabled = lockEnabled;
    }

    public String getAction() { return action; }
    public boolean isLockEnabled() { return lockEnabled; }
}