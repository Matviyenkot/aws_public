package com.task09;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
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
public class ApiHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

	private final WeatherClient weatherClient = new WeatherClient();

	@Override
	public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent request, Context context) {
		String path = request.getPath();
		String method = request.getHttpMethod();

		if ("/weather".equals(path) && "GET".equalsIgnoreCase(method)) {
			try {
				WeatherResponse weatherResponse = weatherClient.getWeather();
				return createResponse(200, weatherResponse.toJson());
			} catch (IOException e) {
				return createResponse(500, "{\"error\": \"Failed to fetch weather data\"}");
			}
		} else {
			String errorMessage = String.format(
					"{\"statusCode\": 400, \"message\": \"Bad request syntax or unsupported method. Request path: %s. HTTP method: %s\"}",
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
}
