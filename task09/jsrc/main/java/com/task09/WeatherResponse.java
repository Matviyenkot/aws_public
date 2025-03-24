package com.task09;

import com.fasterxml.jackson.databind.JsonNode;

public class WeatherResponse {
    private final JsonNode response;

    public WeatherResponse(JsonNode response) {
        this.response = response;
    }

    public String toJson() {
        return response.toString();
    }
}
