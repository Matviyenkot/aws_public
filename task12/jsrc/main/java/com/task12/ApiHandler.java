package com.task12;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.auth0.jwt.JWT;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.syndicate.deployment.annotations.environment.EnvironmentVariable;
import com.syndicate.deployment.annotations.environment.EnvironmentVariables;
import com.syndicate.deployment.annotations.lambda.LambdaHandler;
import com.syndicate.deployment.annotations.resources.DependsOn;
import com.syndicate.deployment.model.ResourceType;
import com.syndicate.deployment.model.RetentionSetting;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminCreateUserRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminCreateUserResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminInitiateAuthRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminInitiateAuthResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminSetUserPasswordRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminSetUserPasswordResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AttributeType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AuthFlowType;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.syndicate.deployment.model.environment.ValueTransformer.USER_POOL_NAME_TO_CLIENT_ID;
import static com.syndicate.deployment.model.environment.ValueTransformer.USER_POOL_NAME_TO_USER_POOL_ID;

@LambdaHandler(
    lambdaName = "api_handler",
	roleName = "api_handler-role",
	isPublishVersion = true,
	aliasName = "${lambdas_alias_name}",
	logsExpiration = RetentionSetting.SYNDICATE_ALIASES_SPECIFIED
)
@DependsOn(resourceType = ResourceType.COGNITO_USER_POOL, name = "${booking_userpool}")
@EnvironmentVariables(value = {
		@EnvironmentVariable(key = "REGION", value = "${region}"),
		@EnvironmentVariable(key = "COGNITO_ID", value = "${booking_userpool}", valueTransformer = USER_POOL_NAME_TO_USER_POOL_ID),
		@EnvironmentVariable(key = "CLIENT_ID", value = "${booking_userpool}", valueTransformer = USER_POOL_NAME_TO_CLIENT_ID),
		@EnvironmentVariable(key = "tables_table", value = "${tables_table}"),
		@EnvironmentVariable(key = "reservations_table", value = "${reservations_table}")
})
public class ApiHandler implements RequestHandler<Map<String, Object>, Map<String, Object>> {

	private final String userPoolId = System.getenv("COGNITO_ID");

	private final String clientId = System.getenv("CLIENT_ID");
	private final CognitoIdentityProviderClient cognitoClient = CognitoIdentityProviderClient.builder()
			.region(Region.of(System.getenv("REGION")))
			.credentialsProvider(DefaultCredentialsProvider.create())
			.build();
	private final ObjectMapper objectMapper = new ObjectMapper();
	private final DynamoDbClient dynamoDbClient = DynamoDbClient.create();
	private final String reservationsTableName = System.getenv("reservations_table");
	private final String tableName = System.getenv("tables_table");

	@Override
	public Map<String, Object> handleRequest(Map<String, Object> request, Context context) {
		context.getLogger().log("reg: " + System.getenv("REGION"));
		context.getLogger().log("COGNITO_ID: " + System.getenv("COGNITO_ID"));
		context.getLogger().log("CLIENT_ID: " + System.getenv("CLIENT_ID"));
		context.getLogger().log("request: " + request);
		context.getLogger().log("containsKey path: " + request.containsKey("path"));
		context.getLogger().log("containsKey httpMethod: " + request.containsKey("httpMethod"));
		String path = (String) request.get("path");
		String httpMethod = (String) request.get("httpMethod");

		context.getLogger().log("path: " + path);
		context.getLogger().log("httpMethod: " + httpMethod);

		if ("/signup".equals(path) && "POST".equalsIgnoreCase(httpMethod)) {
			return handleSignup(request, context);
		} else if ("/signin".equals(path) && "POST".equalsIgnoreCase(httpMethod)) {
			return handleSignin(request, context);
		}

		DecodedJWT decodedJWT = validateAuthorization(request);
		if (decodedJWT == null) {
			return createResponse(401, "Unauthorized: Invalid or missing Authorization header");
		}
		context.getLogger().log("Auth ok!");

		if ("/tables".equals(path) && "GET".equalsIgnoreCase(httpMethod)) {
			return handleGetTables(request);
		} else if ("/tables".equals(path) && "POST".equalsIgnoreCase(httpMethod)) {
			return handleCreateTable(request);
		} else if (path.matches("/tables/\\d+") && "GET".equalsIgnoreCase(httpMethod)) {
			return handleGetTableById(request);
		} else if ("/reservations".equals(path) && "POST".equalsIgnoreCase(httpMethod)) {
			return handleCreateReservation(request);
		} else if ("/reservations".equals(path) && "GET".equalsIgnoreCase(httpMethod)) {
			return handleGetReservations(request);
		}



		return createResponse(404, "Endpoint not found");
	}

	private Map<String, Object> handleSignup(Map<String, Object> request, Context context) {
		try {
			// Parse the request body
			context.getLogger().log("body: " + request.get("body"));
			String body = (String) request.get("body");

			Map<String, Object> requestBody = objectMapper.readValue(body, Map.class);

			String firstName = (String) requestBody.get("firstName");
			String lastName = (String) requestBody.get("lastName");
			String email = (String) requestBody.get("email");
			String password = (String) requestBody.get("password");

			// Validate input
			if (firstName == null || lastName == null || email == null || password == null) {
				return createResponse(400, "Invalid input");
			}

			// Call Cognito signUp API

			AdminCreateUserResponse response = cognitoClient.adminCreateUser(AdminCreateUserRequest.builder()
					.userPoolId(userPoolId)
					.username(email)
					.temporaryPassword(password)
					.userAttributes(
							AttributeType.builder()
									.name("given_name")
									.value(firstName)
									.build(),
							AttributeType.builder()
									.name("family_name")
									.value(lastName)
									.build(),
							AttributeType.builder()
									.name("email")
									.value(email)
									.build())
					.messageAction("SUPPRESS")
					.forceAliasCreation(Boolean.FALSE)
					.build());

			context.getLogger().log("AdminCreateUserRequest response: " + response.toString());


			AdminSetUserPasswordResponse adminSetUserPasswordResponse =	cognitoClient.adminSetUserPassword(AdminSetUserPasswordRequest.builder()
					.userPoolId(userPoolId)
					.username(email)
					.password(password)
					.permanent(true)
					.build());

			context.getLogger().log("adminSetUserPasswordResponse: " + adminSetUserPasswordResponse.toString());

			context.getLogger().log(response.user().toString());
			context.getLogger().log(response.toString());


			return createResponse(200, null);
		} catch (Exception e) {
			context.getLogger().log("Error during sign-up " + e.getMessage());
			return createResponse(400, "Error during sign-up: " + e.getMessage());
		}
	}

	private DecodedJWT validateAuthorization(Map<String, Object> request) {
		try {
			Map<String, String> headers = (Map<String, String>) request.get("headers");
			if (headers == null || !headers.containsKey("Authorization")) {
				throw new IllegalArgumentException("Missing Authorization header");
			}

			// Extract the Authorization header
			String authorizationHeader = headers.get("Authorization");
			if (!authorizationHeader.startsWith("Bearer ")) {
				throw new IllegalArgumentException("Invalid Authorization header format");
			}

			String idToken = authorizationHeader.substring(7); // Extract the token
			DecodedJWT jwt = JWT.decode(idToken);

			return jwt;
		} catch (Exception e) {
			System.out.println("Invalid token!!!! " + request);
			e.printStackTrace();
			return null;
		}
	}

	private Map<String, Object> handleGetTables(Map<String, Object> request) {
		try {
			System.out.println("handleGetTables");

			ScanResponse scanResponse = getAllTables();

			List<Map<String, Object>> tables = scanResponse.items().stream()
					.map(this::generateTable)
					.sorted((t1, t2) -> {
						Integer id1 = (Integer) t1.get("id");
						Integer id2 = (Integer) t2.get("id");
						return id1.compareTo(id2);
					})
					.collect(Collectors.toList());

			// Prepare the response body
			Map<String, Object> responseBody = new LinkedHashMap<>();
			System.out.println("tables: " + responseBody);
			responseBody.put("tables", tables);

			return createResponse(200, objectMapper.writeValueAsString(responseBody));
		} catch (Exception e) {
			e.printStackTrace();
			return createResponse(400, "Error fetching tables: " + e.getMessage());
		}
	}

	private Map<String, Object> generateTable (Map<String, AttributeValue> item) {
		Map<String, Object> table = new LinkedHashMap<>();
		table.put("id", Integer.parseInt(item.get("id").s()));
		table.put("number", Integer.parseInt(item.get("number").n()));
		table.put("places", Integer.parseInt(item.get("places").n()));
		table.put("isVip", item.get("isVip").bool());
		if (item.containsKey("minOrder")) {
			table.put("minOrder", Integer.parseInt(item.get("minOrder").n()));
		}
		return table;
	}

	private Map<String, Object> handleCreateTable(Map<String, Object> request) {
		try {
			System.out.println("handleCreateTable");
			String body = (String) request.get("body");
			Map<String, Object> requestBody = objectMapper.readValue(body, Map.class);

			Integer id = (Integer) requestBody.get("id");
			Integer number = (Integer) requestBody.get("number");
			Integer places = (Integer) requestBody.get("places");
			Boolean isVip = (Boolean) requestBody.get("isVip");
			Integer minOrder = (Integer) requestBody.get("minOrder");

			Map<String, AttributeValue> item = new LinkedHashMap<>();
			item.put("id", AttributeValue.builder().s(String.valueOf(id)).build());
			item.put("number", AttributeValue.builder().n(String.valueOf(number)).build());
			item.put("places", AttributeValue.builder().n(String.valueOf(places)).build());
			item.put("isVip", AttributeValue.builder().bool(isVip).build());
			if (minOrder != null) {
				item.put("minOrder", AttributeValue.builder().n(String.valueOf(minOrder)).build());
			}

			PutItemRequest putItemRequest = PutItemRequest.builder()
					.tableName(tableName)
					.item(item)
					.build();

			dynamoDbClient.putItem(putItemRequest);

			Map<String, Object> responseBody = new LinkedHashMap<>();
			responseBody.put("id", id);

			return createResponse(200, objectMapper.writeValueAsString(responseBody));
		} catch (Exception e) {
			e.printStackTrace();
			return createResponse(400, "Error creating table: " + e.getMessage());
		}
	}


	private Map<String, Object> handleGetTableById(Map<String, Object> request) {
		try {
			System.out.println("handleGetTableById");
			String path = (String) request.get("path");
			String tableId = path.split("/")[2];
			System.out.println("tableId: " + tableId);
			GetItemRequest getItemRequest = GetItemRequest.builder()
					.tableName(tableName)
					.key(Map.of("id", AttributeValue.builder().s(tableId).build()))
					.build();

			GetItemResponse getItemResponse = dynamoDbClient.getItem(getItemRequest);

			if (getItemResponse.item().isEmpty()) {
				return createResponse(404, "Table not found");
			}
			System.out.println("Item: " + getItemResponse.item());

			Map<String, AttributeValue> item = getItemResponse.item();

			Map<String, Object> table = generateTable(item);


			System.out.println("Converted table: " + table);

			return createResponse(200, objectMapper.writeValueAsString(table));
		} catch (Exception e) {
			e.printStackTrace();
			return createResponse(400, "Error fetching table: " + e.getMessage());
		}
	}

	private Map<String, Object> handleSignin(Map<String, Object> request, Context context) {
		try {
			// Parse the request body
			String body = (String) request.get("body");
			Map<String, String> requestBody = objectMapper.readValue(body, Map.class);

			String email = requestBody.get("email");
			String password = requestBody.get("password");

			// Validate input
			if (email == null || password == null) {
				return createResponse(400, "Invalid input");
			}

			// Call Cognito initiateAuth API
			Map<String, String> authParams = new HashMap<>();
			authParams.put("USERNAME", email);
			authParams.put("PASSWORD", password);

			AdminInitiateAuthResponse adminInitiateAuthRequest = cognitoClient.adminInitiateAuth(AdminInitiateAuthRequest.builder()
					.authFlow(AuthFlowType.ADMIN_NO_SRP_AUTH)
					.authParameters(authParams)
					.userPoolId(userPoolId)
					.clientId(clientId)
					.build());

			context.getLogger().log("adminInitiateAuthRequest: " +adminInitiateAuthRequest.toString());
			context.getLogger().log("authenticationResult: " +adminInitiateAuthRequest.authenticationResult());

			if (adminInitiateAuthRequest.authenticationResult() == null) {
				return createResponse(200, null);
			}
			String idToken = adminInitiateAuthRequest.authenticationResult().idToken();

			Map<String, String> responseBody = new HashMap<>();
			responseBody.put("idToken", idToken);

			return createResponse(200, objectMapper.writeValueAsString(responseBody));
		} catch (Exception e) {
			e.printStackTrace();
			return createResponse(400, "Error during sign-in: " + e.getMessage());
		}
	}


	private Map<String, Object> handleCreateReservation(Map<String, Object> request) {
		try {
			// Parse the request body
			String body = (String) request.get("body");
			Map<String, Object> requestBody = objectMapper.readValue(body, Map.class);

			Integer tableNumber = (Integer) requestBody.get("tableNumber");
			String clientName = (String) requestBody.get("clientName");
			String phoneNumber = (String) requestBody.get("phoneNumber");
			String date = (String) requestBody.get("date");
			String slotTimeStart = (String) requestBody.get("slotTimeStart");
			String slotTimeEnd = (String) requestBody.get("slotTimeEnd");

			ScanResponse tables = getAllTables();
			System.out.println("tables: " + tables);
			boolean exists = tables.items().stream()
					.anyMatch(item -> {
						Integer tableNum = Integer.parseInt(item.get("number").n());
						return tableNum.equals(tableNumber);
					});

			if (!exists) {
				return createResponse(400, "Table with number is not exist: " + tableNumber);
			}

			// Validate input
			if (clientName == null || phoneNumber == null || date == null || slotTimeStart == null || slotTimeEnd == null) {
				return createResponse(400, "Invalid input");
			}

//			// Generate a UUID for the reservation
			UUID reservationId = UUID.randomUUID();

			// Check for conflicting reservations
			ScanRequest scanRequest = ScanRequest.builder()
					.tableName(reservationsTableName)
					.build();

			ScanResponse scanResponse = dynamoDbClient.scan(scanRequest);
			List<Map<String, AttributeValue>> existingReservations = scanResponse.items();

			for (Map<String, AttributeValue> reservation : existingReservations) {
				int existingTableNumber = Integer.parseInt(reservation.get("tableNumber").n());
				String existingDate = reservation.get("date").s();
				String existingStart = reservation.get("slotTimeStart").s();
				String existingEnd = reservation.get("slotTimeEnd").s();

				if (tableNumber.equals(existingTableNumber) && date.equals(existingDate) &&
						isTimeConflict(slotTimeStart, slotTimeEnd, existingStart, existingEnd)) {
					return createResponse(400, "Conflicting reservation exists for table " + tableNumber);
				}
			}


			// Add the reservation to DynamoDB
			Map<String, AttributeValue> item = new LinkedHashMap<>();
			item.put("id", AttributeValue.builder().s(reservationId.toString()).build());
			item.put("tableNumber", AttributeValue.builder().n(String.valueOf(tableNumber)).build());
			item.put("clientName", AttributeValue.builder().s(clientName).build());
			item.put("phoneNumber", AttributeValue.builder().s(phoneNumber).build());
			item.put("date", AttributeValue.builder().s(date).build());
			item.put("slotTimeStart", AttributeValue.builder().s(slotTimeStart).build());
			item.put("slotTimeEnd", AttributeValue.builder().s(slotTimeEnd).build());

			PutItemRequest putItemRequest = PutItemRequest.builder()
					.tableName(reservationsTableName)
					.item(item)
					.build();

			dynamoDbClient.putItem(putItemRequest);

			Map<String, Object> responseBody = new LinkedHashMap<>();
			responseBody.put("reservationId", reservationId.toString());

			return createResponse(200, objectMapper.writeValueAsString(responseBody));
		} catch (Exception e) {
			e.printStackTrace();
			return createResponse(400, "Error creating reservation: " + e.getMessage());
		}
	}

	private Map<String, Object> handleGetReservations(Map<String, Object> request) {
		try {
			System.out.println("handleGetReservations");
			ScanRequest scanRequest = ScanRequest.builder()
					.tableName(reservationsTableName)
					.build();

			ScanResponse scanResponse = dynamoDbClient.scan(scanRequest);

			List<Map<String, AttributeValue>> items  = scanResponse.items();
			System.out.println("Items count: " + scanResponse.count());
			System.out.println("Items: " + items);

			List<Map<String, Object>> reservations = items.stream()
					.map(item -> {
						Map<String, Object> reservation = new LinkedHashMap<>();
						reservation.put("tableNumber", Integer.parseInt(item.get("tableNumber").n()));
						reservation.put("clientName", item.get("clientName").s());
						reservation.put("phoneNumber", item.get("phoneNumber").s());
						reservation.put("date", item.get("date").s());
						reservation.put("slotTimeStart", item.get("slotTimeStart").s());
						reservation.put("slotTimeEnd", item.get("slotTimeEnd").s());
						return reservation;
					})
					.sorted((r1, r2) -> {
						int tableNumber1 = (int) r1.get("tableNumber");
						int tableNumber2 = (int) r2.get("tableNumber");
						return Integer.compare(tableNumber1, tableNumber2);
					})
					.collect(Collectors.toList());

			Map<String, Object> responseBody = new LinkedHashMap<>();
			responseBody.put("reservations", reservations);
			System.out.println("return reservations: " + responseBody);
			return createResponse(200, objectMapper.writeValueAsString(responseBody));
		} catch (Exception e) {
			e.printStackTrace();
			return createResponse(400, "Error fetching reservations: " + e.getMessage());
		}
	}

	private boolean isTimeConflict(String start1, String end1, String start2, String end2) {
		return (start1.compareTo(end2) < 0 && end1.compareTo(start2) > 0);
	}



	private Map<String, Object> createResponse(int statusCode, String body) {
		Map<String, Object> response = new HashMap<>();
		response.put("statusCode", statusCode);
		response.put("headers", Map.of(
				"Content-Type", "application/json",
				"Access-Control-Allow-Headers", "Content-Type,X-Amz-Date,Authorization,X-Api-Key,X-Amz-Security-Token",
				"Access-Control-Allow-Origin", "*",
				"Access-Control-Allow-Methods", "*",
				"Accept-Version", "*"
		));
		response.put("body", body);
		response.put("isBase64Encoded", false);
		return response;
	}

	public ScanResponse getAllTables() {
		ScanRequest scanRequest = ScanRequest.builder()
				.tableName(tableName)
				.build();

		return dynamoDbClient.scan(scanRequest);
	}
}
