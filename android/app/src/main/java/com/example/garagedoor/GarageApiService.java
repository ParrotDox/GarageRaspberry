package com.example.garagedoor;

import java.util.List;
import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.GET;
import retrofit2.http.POST;

public interface GarageApiService {

    @GET("/api/door/status")
    Call<DoorStatusResponse> getStatus();

    @GET("/api/door/logs")
    Call<List<LogEntry>> getLogs();

    @POST("/api/door/command")
    Call<Void> sendCommand(@Body DoorCommandRequest request);
}