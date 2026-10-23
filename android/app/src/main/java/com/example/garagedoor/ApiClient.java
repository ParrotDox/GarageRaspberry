package com.example.garagedoor;

import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

public class ApiClient {
    private static GarageApiService service;
    private static String currentBaseUrl = "";

    public static synchronized GarageApiService getService(String baseUrl) {
        // Добавляем слеш в конец адреса, если пользователь забыл его ввести
        if (!baseUrl.endsWith("/")) {
            baseUrl += "/";
        }

        // Пересоздаём экземпляр Retrofit только если адрес изменился
        if (service == null || !currentBaseUrl.equals(baseUrl)) {
            currentBaseUrl = baseUrl;
            Retrofit retrofit = new Retrofit.Builder()
                    .baseUrl(currentBaseUrl)
                    .addConverterFactory(GsonConverterFactory.create())
                    .build();
            service = retrofit.create(GarageApiService.class);
        }
        return service;
    }
}