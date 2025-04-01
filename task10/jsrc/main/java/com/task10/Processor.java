package com.task10;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.syndicate.deployment.annotations.environment.EnvironmentVariable;
import com.syndicate.deployment.annotations.environment.EnvironmentVariables;
import com.syndicate.deployment.annotations.lambda.LambdaHandler;
import com.syndicate.deployment.annotations.lambda.LambdaLayer;
import com.syndicate.deployment.annotations.lambda.LambdaUrlConfig;
import com.syndicate.deployment.annotations.resources.DependsOn;
import com.syndicate.deployment.model.ArtifactExtension;
import com.syndicate.deployment.model.DeploymentRuntime;
import com.syndicate.deployment.model.ResourceType;
import com.syndicate.deployment.model.RetentionSetting;
import com.syndicate.deployment.model.TracingMode;
import com.syndicate.deployment.model.lambda.url.AuthType;
import com.syndicate.deployment.model.lambda.url.InvokeMode;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.json.JSONObject;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

import java.io.IOException;
import java.util.HashMap;
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
public class Processor implements RequestHandler<Object, Map<String, Object>> {

	private static final String URL = "https://api.open-meteo.com/v1/forecast?latitude=52.52&longitude=13.41&current=temperature_2m,wind_speed_10m&hourly=temperature_2m,relative_humidity_2m,wind_speed_10m";
	private final DynamoDbClient db = DynamoDbClient.builder()
			.credentialsProvider(DefaultCredentialsProvider.create())
			.region(Region.EU_WEST_1)
			.build();

	public Map<String, Object> handleRequest(Object request, Context context) {
		LambdaLogger log = context.getLogger();
		Map<String, Object> result = new HashMap<>();
		try {
			String weatherRaw = getWeatherData();
			JSONObject weatherJSON = new JSONObject(weatherRaw);
			storeWeatherData(weatherJSON);

			result.put("statusCode", 200);
			result.put("message", "ALL DONE");
		} catch (IOException e) {
			log.log("Not able to get data from the Weather service:" + e.getMessage());
			result.put("statusCode", 500);
			result.put("error", "Not able to get data from the Weather service:" + e.getMessage());
		} catch (DynamoDbException e) {
			log.log("Not able to store data to DB:" + e.getMessage());
			result.put("statusCode", 500);
			result.put("error", "ot able to store data to DB:" + e.getMessage());
		}
		return result;
	}

	private String getWeatherData() throws IOException {
		try (CloseableHttpClient http = HttpClients.createDefault()) {
			HttpGet request = new HttpGet(URL);
			HttpResponse response = http.execute(request);
			return EntityUtils.toString(response.getEntity());
		}
	}

	private void storeWeatherData(JSONObject jsonObject) throws DynamoDbException {
		Map<String, AttributeValue> itemValues = new HashMap<>();
		itemValues.put("id", AttributeValue.builder().s(UUID.randomUUID().toString()).build());
		JSONObject hourlyJson = jsonObject.getJSONObject("hourly");
		Map<String, AttributeValue> hourlyMap = new HashMap<>();
		hourlyMap.put("time", AttributeValue.builder().l(
				hourlyJson.getJSONArray("time").toList().stream()
						.map(times -> AttributeValue.builder().s(times.toString()).build())
						.collect(Collectors.toList())
		).build());
		hourlyMap.put("temperature_2m", AttributeValue.builder().l(
				hourlyJson.getJSONArray("temperature_2m").toList().stream()
						.map(temp -> AttributeValue.builder().n(temp.toString()).build())
						.collect(Collectors.toList())
		).build());

		JSONObject hourlyUnitsJson = jsonObject.getJSONObject("hourly_units");
		Map<String, AttributeValue> hourlyUnitsMap = new HashMap<>();
		hourlyUnitsMap.put("time", AttributeValue.builder().s(hourlyUnitsJson.getString("time")).build());
		hourlyUnitsMap.put("temperature_2m", AttributeValue.builder().s(hourlyUnitsJson.getString("temperature_2m")).build());

		Map<String, AttributeValue> forecastMap = new HashMap<>();
		forecastMap.put("elevation", AttributeValue.builder().n(String.valueOf(jsonObject.getDouble("elevation"))).build());
		forecastMap.put("generationtime_ms", AttributeValue.builder().n(String.valueOf(jsonObject.getDouble("generationtime_ms"))).build());
		forecastMap.put("latitude", AttributeValue.builder().n(String.valueOf(jsonObject.getDouble("latitude"))).build());
		forecastMap.put("longitude", AttributeValue.builder().n(String.valueOf(jsonObject.getDouble("longitude"))).build());
		forecastMap.put("timezone", AttributeValue.builder().s(jsonObject.getString("timezone")).build());
		forecastMap.put("timezone_abbreviation", AttributeValue.builder().s(jsonObject.getString("timezone_abbreviation")).build());
		forecastMap.put("utc_offset_seconds", AttributeValue.builder().n(String.valueOf(jsonObject.getInt("utc_offset_seconds"))).build());
		forecastMap.put("hourly", AttributeValue.builder().m(hourlyMap).build());
		forecastMap.put("hourly_units", AttributeValue.builder().m(hourlyUnitsMap).build());

		itemValues.put("forecast", AttributeValue.builder().m(forecastMap).build());
		String tableName = System.getenv("table");
		if (tableName == null) {
			throw new IllegalStateException("Table name environment variable 'table' is not set");
		}
		PutItemRequest request = PutItemRequest.builder()
				.tableName(tableName)
				.item(itemValues)
				.build();
		db.putItem(request);
		System.out.println("Item inserted successfully!");
	}
}
