package com.task09;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.syndicate.deployment.annotations.lambda.LambdaHandler;
import com.syndicate.deployment.annotations.lambda.LambdaLayer;
import com.syndicate.deployment.annotations.lambda.LambdaUrlConfig;
import com.syndicate.deployment.model.ArtifactExtension;
import com.syndicate.deployment.model.DeploymentRuntime;
import com.syndicate.deployment.model.RetentionSetting;
import com.syndicate.deployment.model.lambda.url.AuthType;
import com.syndicate.deployment.model.lambda.url.InvokeMode;
import com.fasterxml.jackson.databind.JsonNode;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

@LambdaHandler(
    lambdaName = "api_handler",
	roleName = "api_handler-role",
	isPublishVersion = true,
	aliasName = "${lambdas_alias_name}",
	logsExpiration = RetentionSetting.SYNDICATE_ALIASES_SPECIFIED
)
@LambdaUrlConfig(
		authType = AuthType.NONE,
		invokeMode = InvokeMode.BUFFERED
)
@LambdaLayer(
		layerName = "weather_sdk",
		libraries = {"lib/weather-sdk-1.0.0.jar"},
		runtime = DeploymentRuntime.JAVA11,
		artifactExtension = ArtifactExtension.ZIP
)
public class ApiHandler implements RequestHandler<Map<String, Object>, Map<String, Object>> {

	private static final String WEATHER_API_URL = "https://api.open-meteo.com/v1/forecast?latitude=50.4375&longitude=30.5&current=temperature_2m,wind_speed_10m&hourly=temperature_2m,relative_humidity_2m,wind_speed_10m";
	private final OkHttpClient httpClient = new OkHttpClient();
	private final ObjectMapper objectMapper = new ObjectMapper();

	@Override
	public Map<String, Object> handleRequest(Map<String, Object> event, Context context) {
		String path = (String) event.get("path");
		String method = (String) event.get("httpMethod");

		if ("/weather".equals(path) && "GET".equalsIgnoreCase(method)) {
			return getWeatherResponse();
		} else {
			return getBadRequestResponse(path, method);
		}
	}

	private Map<String, Object> getWeatherResponse() {
		try {
			Request request = new Request.Builder()
					.url(WEATHER_API_URL)
					.build();

			Response response = httpClient.newCall(request).execute();
			if (!response.isSuccessful()) {
				throw new IOException("Unexpected response code: " + response.code());
			}

			JsonNode weatherData = objectMapper.readTree(response.body().string());
			Map<String, Object> responseBody = new HashMap<>();
			responseBody.put("statusCode", 200);
			responseBody.put("body", weatherData);
			responseBody.put("headers", Map.of("content-type", "application/json"));
			responseBody.put("isBase64Encoded", false);
			return responseBody;

		} catch (IOException e) {
			Map<String, Object> errorResponse = new HashMap<>();
			errorResponse.put("statusCode", 500);
			errorResponse.put("body", Map.of("message", "Failed to fetch weather data"));
			errorResponse.put("headers", Map.of("content-type", "application/json"));
			errorResponse.put("isBase64Encoded", false);
			return errorResponse;
		}
	}

	private Map<String, Object> getBadRequestResponse(String path, String method) {
		Map<String, Object> responseBody = new HashMap<>();
		responseBody.put("statusCode", 400);
		responseBody.put("body", Map.of(
				"statusCode", 400,
				"message", "Bad request syntax or unsupported method. Request path: " + path + ". HTTP method: " + method
		));
		responseBody.put("headers", Map.of("content-type", "application/json"));
		responseBody.put("isBase64Encoded", false);
		return responseBody;
	}
}
