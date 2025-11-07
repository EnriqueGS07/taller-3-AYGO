package org.aygo;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.google.gson.Gson;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import com.mongodb.client.result.UpdateResult;

import org.bson.Document;

import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public class Users implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {
    private static final String HTTP_METHOD_GET = "GET";
    private static final String HTTP_METHOD_POST = "POST";
    private static final String HTTP_METHOD_PUT = "PUT";
    private static final String MESSAGE_METHOD_NOT_ALLOWED = "Method not allowed";
    private static final String MESSAGE_USER_NOT_FOUND = "User not found";
    private static final String MESSAGE_INVALID_BODY = "Invalid request body";
    private static final String MESSAGE_INTERNAL_ERROR = "Internal server error";
    private static final String QUERY_PARAM_USER_ID = "id";

    private static final String USER_ID_PREFIX = "u_";

    private static final String ENV_MONGO_URI = "MONGO_URI";
    private static final String ENV_MONGO_DB = "MONGO_DB";
    private static final String ENV_MONGO_COLLECTION = "MONGO_COLLECTION";

    private static final String FIELD_ID = "id";
    private static final String FIELD_NAME = "name";
    private static final String FIELD_TRAVELING = "traveling";
    private static final String FIELD_TRAVEL = "travel";

    private static final MongoClient MONGO_CLIENT = createMongoClient();
    private static final MongoDatabase MONGO_DATABASE = createMongoDatabase(MONGO_CLIENT);
    private static final MongoCollection<Document> USERS_COLLECTION = createUsersCollection(MONGO_DATABASE);

    private final Gson gson = new Gson();

    private static MongoClient createMongoClient() {
        String mongoUri = System.getenv(ENV_MONGO_URI);
        if (mongoUri == null || mongoUri.isBlank()) {
            throw new IllegalStateException("Missing environment variable: " + ENV_MONGO_URI);
        }
        return MongoClients.create(mongoUri);
    }

    private static MongoDatabase createMongoDatabase(MongoClient client) {
        String databaseName = System.getenv(ENV_MONGO_DB);
        if (databaseName == null || databaseName.isBlank()) {
            throw new IllegalStateException("Missing environment variable: " + ENV_MONGO_DB);
        }
        return client.getDatabase(databaseName);
    }

    private static MongoCollection<Document> createUsersCollection(MongoDatabase database) {
        String collectionName = System.getenv(ENV_MONGO_COLLECTION);
        if (collectionName == null || collectionName.isBlank()) {
            throw new IllegalStateException("Missing environment variable: " + ENV_MONGO_COLLECTION);
        }
        return database.getCollection(collectionName);
    }

    private static class CreateUserRequest {
        private String name;

        String getName() {
            return name;
        }
    }

    private static class TravelUpdateRequest {
        private String id;
        private boolean traveling;
        private String rideId;

        String getId() {
            return id;
        }

        boolean isTraveling() {
            return traveling;
        }

        String getRideId() {
            return rideId;
        }
    }

    @SuppressWarnings("unused")
    private static class UserSummary {
        private final String id;
        private final String name;
        private final boolean traveling;
        private final String travel;

        UserSummary(Document source) {
            this.id = source.getString(FIELD_ID);
            this.name = source.getString(FIELD_NAME);
            this.traveling = source.getBoolean(FIELD_TRAVELING, false);
            this.travel = source.getString(FIELD_TRAVEL);
        }
    }

    private String serializeUsers() {
        ArrayList<UserSummary> summaries = new ArrayList<>();
        USERS_COLLECTION.find().map(UserSummary::new).into(summaries);
        return gson.toJson(summaries);
    }

    private String serializeUser(Document document) {
        return gson.toJson(new UserSummary(document));
    }

    private Optional<Document> findUserById(String userId) {
        if (userId == null || userId.isBlank()) {
            return Optional.empty();
        }
        Document document = USERS_COLLECTION.find(Filters.eq(FIELD_ID, userId)).first();
        return Optional.ofNullable(document);
    }

    private APIGatewayProxyResponseEvent createUser(APIGatewayProxyRequestEvent input) {
        CreateUserRequest request = gson.fromJson(input.getBody(), CreateUserRequest.class);
        if (request == null || request.getName() == null || request.getName().isBlank()) {
            return buildErrorResponse(400, MESSAGE_INVALID_BODY);
        }
        String userId = USER_ID_PREFIX + UUID.randomUUID();
        Document document = new Document()
                .append(FIELD_ID, userId)
                .append(FIELD_NAME, request.getName())
                .append(FIELD_TRAVELING, false)
                .append(FIELD_TRAVEL, null);
        USERS_COLLECTION.insertOne(document);
        APIGatewayProxyResponseEvent responseEvent = new APIGatewayProxyResponseEvent();
        responseEvent.setStatusCode(201);
        responseEvent.setBody(serializeUser(document));
        return responseEvent;
    }

    private APIGatewayProxyResponseEvent updateTravel(APIGatewayProxyRequestEvent input) {
        TravelUpdateRequest request = gson.fromJson(input.getBody(), TravelUpdateRequest.class);
        if (request == null || request.getId() == null || request.getId().isBlank()) {
            return buildErrorResponse(400, MESSAGE_INVALID_BODY);
        }
        UpdateResult updateResult = USERS_COLLECTION.updateOne(
                Filters.eq(FIELD_ID, request.getId()),
                Updates.combine(
                        Updates.set(FIELD_TRAVELING, request.isTraveling()),
                        Updates.set(FIELD_TRAVEL, request.getRideId())
                )
        );
        if (updateResult.getMatchedCount() == 0) {
            return buildErrorResponse(404, MESSAGE_USER_NOT_FOUND);
        }
        Optional<Document> user = findUserById(request.getId());
        if (user.isEmpty()) {
            return buildErrorResponse(404, MESSAGE_USER_NOT_FOUND);
        }
        APIGatewayProxyResponseEvent responseEvent = new APIGatewayProxyResponseEvent();
        responseEvent.setStatusCode(200);
        responseEvent.setBody(serializeUser(user.get()));
        return responseEvent;
    }

    private APIGatewayProxyResponseEvent buildGetResponse(APIGatewayProxyRequestEvent input) {
        Map<String, String> parameters = input.getQueryStringParameters();
        if (parameters != null && parameters.containsKey(QUERY_PARAM_USER_ID)) {
            String userId = parameters.get(QUERY_PARAM_USER_ID);
            if (userId == null || userId.isBlank()) {
                return buildErrorResponse(400, MESSAGE_INVALID_BODY);
            }
            Optional<Document> user = findUserById(userId);
            if (user.isEmpty()) {
                return buildErrorResponse(404, MESSAGE_USER_NOT_FOUND);
            }
            APIGatewayProxyResponseEvent responseEvent = new APIGatewayProxyResponseEvent();
            responseEvent.setStatusCode(200);
            responseEvent.setBody(serializeUser(user.get()));
            return responseEvent;
        }
        APIGatewayProxyResponseEvent responseEvent = new APIGatewayProxyResponseEvent();
        responseEvent.setStatusCode(200);
        responseEvent.setBody(serializeUsers());
        return responseEvent;
    }

    private APIGatewayProxyResponseEvent buildErrorResponse(int statusCode, String message) {
        APIGatewayProxyResponseEvent responseEvent = new APIGatewayProxyResponseEvent();
        responseEvent.setStatusCode(statusCode);
        responseEvent.setBody(message);
        return responseEvent;
    }

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent input, Context context) {
        try {
            String method = input.getHttpMethod();
            if (HTTP_METHOD_GET.equals(method)) {
                return buildGetResponse(input);
            }
            if (HTTP_METHOD_POST.equals(method)) {
                return createUser(input);
            }
            if (HTTP_METHOD_PUT.equals(method)) {
                return updateTravel(input);
            }
            return buildErrorResponse(405, MESSAGE_METHOD_NOT_ALLOWED);
        } catch (RuntimeException exception) {
            if (context != null && context.getLogger() != null) {
                context.getLogger().log(exception.getMessage());
            }
            return buildErrorResponse(500, MESSAGE_INTERNAL_ERROR);
        }
    }
}