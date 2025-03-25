package com.task08;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.syndicate.deployment.annotations.environment.EnvironmentVariable;
import com.syndicate.deployment.annotations.environment.EnvironmentVariables;
import com.syndicate.deployment.annotations.events.RuleEventSource;
import com.syndicate.deployment.annotations.lambda.LambdaHandler;
import com.syndicate.deployment.model.RetentionSetting;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;


@LambdaHandler(
    lambdaName = "uuid_generator",
	roleName = "uuid_generator-role",
	isPublishVersion = true,
	aliasName = "${lambdas_alias_name}",
	logsExpiration = RetentionSetting.SYNDICATE_ALIASES_SPECIFIED
)
@RuleEventSource(
		targetRule = "uuid_trigger"
)
@EnvironmentVariables(value = {
		@EnvironmentVariable(key = "region", value = "${region}"),
		@EnvironmentVariable(key = "bucket", value = "${target_bucket}")})
public class UuidGenerator implements RequestHandler<Object, String> {

	private static final String BUCKET_NAME = System.getenv("bucket");

	@Override
	public String handleRequest(Object input, Context context) {
		AmazonS3 s3Client = AmazonS3ClientBuilder.defaultClient();
		ObjectMapper objectMapper = new ObjectMapper();

		// 1️⃣ Отримуємо поточний час у форматі ISO 8601 (UTC)
		String timestamp = Instant.now().toString(); // "2024-01-01T00:00:00.000Z"

		// 2️⃣ Генеруємо 10 випадкових UUID
		List<String> uuids = IntStream.range(0, 10)
				.mapToObj(i -> UUID.randomUUID().toString())
				.collect(Collectors.toList());

		// 3️⃣ Формуємо JSON-структуру
		String jsonContent;
		try {
			jsonContent = objectMapper.writeValueAsString(new UuidList(uuids));
		} catch (Exception e) {
			context.getLogger().log("JSON serialization error: " + e.getMessage());
			return "Error creating JSON";
		}

		// 4️⃣ Записуємо у S3
		s3Client.putObject(BUCKET_NAME, timestamp, new ByteArrayInputStream(jsonContent.getBytes(StandardCharsets.UTF_8)), null);

		context.getLogger().log("UUIDs saved to S3: " + timestamp);
		return "File " + timestamp + " successfully created in bucket " + BUCKET_NAME;
	}

	// Клас для збереження UUID у форматі JSON
	static class UuidList {
		public List<String> ids;

		public UuidList(List<String> ids) {
			this.ids = ids;
		}
	}
}
