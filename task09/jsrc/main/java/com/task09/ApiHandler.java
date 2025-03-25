package com.task09;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.syndicate.deployment.annotations.lambda.LambdaHandler;
import com.syndicate.deployment.annotations.lambda.LambdaLayer;
import com.syndicate.deployment.annotations.lambda.LambdaUrlConfig;
import com.syndicate.deployment.model.ArtifactExtension;
import com.syndicate.deployment.model.DeploymentRuntime;
import com.syndicate.deployment.model.RetentionSetting;
import com.syndicate.deployment.model.lambda.url.AuthType;
import com.syndicate.deployment.model.lambda.url.InvokeMode;
import org.example.WeatherClient;
import org.example.WeatherResponse;

import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

@LambdaHandler(
    lambdaName = "api_handler",
	roleName = "api_handler-role",
	isPublishVersion = true,
	aliasName = "${lambdas_alias_name}",
	logsExpiration = RetentionSetting.SYNDICATE_ALIASES_SPECIFIED,
	layers = {"weather_sdk"}
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
public class ApiHandler implements RequestHandler<Object, APIGatewayProxyResponseEvent> {

	private final WeatherClient weatherClient = new WeatherClient();
	private static final ObjectMapper objectMapper = new ObjectMapper();


	@Override
	public APIGatewayProxyResponseEvent handleRequest(Object request, Context context) {

		Map<String, String> data = getPathAndMethod(request);

		String path = data.get("path");
		String method = data.get("method");

		if ("/weather".equals(path) && "GET".equalsIgnoreCase(method)) {
			try {
				WeatherResponse weatherResponse = weatherClient.getWeather();
				Map<String, Object> responseMap = new HashMap<>();
				responseMap.put("statusCode", 200);
				responseMap.put("body", weatherResponse.toJson());
				return createResponse(200, objectMapper.writeValueAsString(responseMap));
			} catch (IOException e) {
				return createResponse(500, "{\"error\": \"Failed to fetch weather data\"}");
			}
		} else {
			String errorMessage = String.format(
					"{\"statusCode\": 400, \"message\": \"Very Bad request syntax or unsupported method. Request path: %s. HTTP method: %s\"}",
					path, method);
			return createResponse(400, errorMessage);
		}
	}

	private APIGatewayProxyResponseEvent createResponse(int statusCode, String body) {
		Map<String, String> headers = new HashMap<>();
		headers.put("content-type", "application/json");

		return new APIGatewayProxyResponseEvent()
				.withStatusCode(statusCode)
				.withBody(body)
				.withHeaders(headers);
	}

	private Map<String, String> getPathAndMethod(Object request) {

		ObjectMapper objectMapper = new ObjectMapper();
		Map<String, String> result = new HashMap<>();

		try {
			// Конвертація JSON у мапу
			Map<String, Object> data = objectMapper.convertValue(request, LinkedHashMap.class);

			// Отримання requestContext
			Map<String, Object> requestContext = (Map<String, Object>) data.get("requestContext");

			// Отримання http
			Map<String, Object> http = (Map<String, Object>) requestContext.get("http");

			// Отримання path та method
			String path = (String) http.get("path");
			String method = (String) http.get("method");

			result.put("path", path);
			result.put("method", method);
		} catch (Exception e) {
			e.printStackTrace();
		}
		return result;
	}
}
