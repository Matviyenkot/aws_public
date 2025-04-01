package com.task10;

import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;
import com.amazonaws.services.dynamodbv2.document.DynamoDB;
import com.amazonaws.services.dynamodbv2.document.Item;
import com.amazonaws.services.dynamodbv2.document.Table;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.amazonaws.xray.AWSXRay;
import com.amazonaws.xray.entities.Segment;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.syndicate.deployment.annotations.environment.EnvironmentVariable;
import com.syndicate.deployment.annotations.environment.EnvironmentVariables;
import com.syndicate.deployment.annotations.lambda.LambdaHandler;
import com.syndicate.deployment.annotations.lambda.LambdaLayer;
import com.syndicate.deployment.annotations.lambda.LambdaUrlConfig;
import com.syndicate.deployment.model.ArtifactExtension;
import com.syndicate.deployment.model.DeploymentRuntime;
import com.syndicate.deployment.model.RetentionSetting;
import com.syndicate.deployment.model.TracingMode;
import com.syndicate.deployment.model.lambda.url.AuthType;
import com.syndicate.deployment.model.lambda.url.InvokeMode;
import org.example.WeatherClient;
import org.example.WeatherResponse;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@LambdaHandler(
    lambdaName = "processor",
	roleName = "processor-role",
	isPublishVersion = true,
	aliasName = "${lambdas_alias_name}",
	logsExpiration = RetentionSetting.SYNDICATE_ALIASES_SPECIFIED,
	tracingMode = TracingMode.Active,
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
@EnvironmentVariables(value = {
		@EnvironmentVariable(key = "region", value = "${region}"),
		@EnvironmentVariable(key = "table", value = "${target_table}")})
public class Processor implements RequestHandler<Object, APIGatewayProxyResponseEvent> {

	private final WeatherClient weatherClient = new WeatherClient();
	private static final ObjectMapper objectMapper = new ObjectMapper();
	private static final AmazonDynamoDB client = AmazonDynamoDBClientBuilder.defaultClient();
//	private static final DynamoDB dynamoDB = new DynamoDB(client);
	private static final DynamoDbClient dynamoDB = DynamoDbClient.create();
	private static final String TABLE_NAME = "Weather";

	@Override
	public APIGatewayProxyResponseEvent handleRequest(Object request, Context context) {
		Segment segment = AWSXRay.beginSegment("HandleWeatherRequest");
		try {
			Map<String, String> data = getPathAndMethod(request);
//			String path = data.get("path");
//			String method = data.get("method");

			WeatherResponse weatherResponse = weatherClient.getWeather();
			saveWeatherToDynamoDB(weatherResponse);
			return createResponse(200, weatherResponse.toJson());
		} catch (IOException e) {
			return createResponse(500, "{\"error\": \"Failed to fetch weather data\"}");
		} finally {
			AWSXRay.endSegment();
		}
	}

	private void saveWeatherToDynamoDB(WeatherResponse weatherResponse) {
		Segment segment = AWSXRay.beginSegment("SaveWeatherToDynamoDB");
		try {

			Map<String, AttributeValue> itemMap = new HashMap<>();
			itemMap.put("id", AttributeValue.builder().s(UUID.randomUUID().toString()).build());

			Map<String, Object> body = objectMapper.readValue(weatherResponse.toJson(), LinkedHashMap.class);
			itemMap.put("forecast", AttributeValue.builder().m(
					body.entrySet().stream()
							.filter(entry -> List.of(
									"elevation",
									"generationtime_ms",
									"hourly",
									"hourly_units",
									"latitude",
									"longitude",
									"timezone",
									"timezone_abbreviation",
									"utc_offset_seconds"
							).contains(entry.getKey()))
							.collect(Collectors.toMap(
									Map.Entry::getKey,
									e -> convertToAttributeValue(e.getKey(), e.getValue())
							))
			).build());

			PutItemRequest putItemRequest = PutItemRequest.builder()
					.tableName(System.getenv("table"))
					.item(itemMap)
					.build();
//			Item item = new Item()
//					.withPrimaryKey("id", UUID.randomUUID().toString())
//					.withJSON("forecast", objectMapper.writeValueAsString(weatherResponse));
			dynamoDB.putItem(putItemRequest);
		} catch (Exception e) {
			segment.addException(e);
		} finally {
			AWSXRay.endSegment();
		}
	}

	private AttributeValue convertToAttributeValue(String key, Object value) {
		// Dynamic type handling based on key and value type
		switch (key) {
			case "elevation":
			case "generationtime_ms":
			case "latitude":
			case "longitude":
			case "utc_offset_seconds":
				// Convert numeric values to Number Attribute
				return AttributeValue.builder().n(String.valueOf(value)).build();
			case "timezone":
			case "timezone_abbreviation":
				// Convert string values to String Attribute
				return AttributeValue.builder().s(String.valueOf(value)).build();
			case "hourly":
			case "hourly_units":
				// Handle nested objects (maps or lists)
				if (value instanceof Map) {
					Map<String, Object> nestedMap = (Map<String, Object>) value;
					return AttributeValue.builder()
							.m(nestedMap.entrySet().stream()
									.collect(Collectors.toMap(
											Map.Entry::getKey,
											e -> convertToAttributeValue(e.getKey(), e.getValue())
									))).build();
				} else if (value instanceof List) {
					return AttributeValue.builder().l(
							((List<Object>) value).stream()
									.map(v -> convertToAttributeValue("", v)) // Handle list elements
									.collect(Collectors.toList())
					).build();
				}
				throw new IllegalArgumentException(key + " expects a nested structure (map or list).");
			default:
				// Handle individual list items
				if (value instanceof Number) {
					return AttributeValue.builder().n(String.valueOf(value)).build();
				} else if (value instanceof String) {
					return AttributeValue.builder().s((String) value).build();
				} else if (value instanceof List) {
					return AttributeValue.builder()
							.l(((List<Object>) value).stream()
									.map(v -> convertToAttributeValue("", v))
									.collect(Collectors.toList()))
							.build();
				} else {
					throw new IllegalArgumentException("Unsupported type for key: " + key);
				}
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
			Map<String, Object> data = objectMapper.convertValue(request, LinkedHashMap.class);
			Map<String, Object> requestContext = (Map<String, Object>) data.get("requestContext");
			Map<String, Object> http = (Map<String, Object>) requestContext.get("http");

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
