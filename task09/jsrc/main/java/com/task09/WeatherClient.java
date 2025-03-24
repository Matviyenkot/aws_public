package com.task09;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;

public class WeatherClient {
    private static final String API_URL = "https://api.open-meteo.com/v1/forecast";
    private static final double LATITUDE = 50.4375;
    private static final double LONGITUDE = 30.5;
    private static final String QUERY_PARAMS = "?latitude=" + LATITUDE + "&longitude=" + LONGITUDE +
            "&current=temperature_2m,wind_speed_10m&hourly=temperature_2m,relative_humidity_2m,wind_speed_10m";

    private final OkHttpClient client;
    private final ObjectMapper objectMapper;

    public WeatherClient() {
        this.client = new OkHttpClient();
        this.objectMapper = new ObjectMapper();
    }

    public WeatherResponse getWeather() throws IOException {
        Request request = new Request.Builder()
                .url(API_URL + QUERY_PARAMS)
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Unexpected code: " + response);
            }
            JsonNode jsonNode = objectMapper.readTree(response.body().string());
            return new WeatherResponse(jsonNode);
        }
    }
}
