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

public class Rides implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {
    private static final String HTTP_METHOD_GET = "GET";
    private static final String HTTP_METHOD_POST = "POST";
    private static final String HTTP_METHOD_PUT = "PUT";
    private static final String MESSAGE_METHOD_NOT_ALLOWED = "Method not allowed";
    private static final String MESSAGE_RIDE_NOT_FOUND = "Ride not found";
    private static final String MESSAGE_INVALID_BODY = "Invalid request body";
    private static final String MESSAGE_INTERNAL_ERROR = "Internal server error";

    private static final String QUERY_PARAM_RIDE_ID = "id";

    private static final String RIDE_ID_PREFIX = "r_";

    private static final String ENV_MONGO_URI = "MONGO_URI";
    private static final String ENV_MONGO_DB = "MONGO_DB";
    private static final String ENV_MONGO_COLLECTION = "MONGO_COLLECTION";

    private static final String FIELD_ID = "id";
    private static final String FIELD_DRIVER = "driver";
    private static final String FIELD_AVAILABLE = "available";
    private static final String FIELD_PASSENGER_ID = "passengerId";

    private static final int STATUS_CODE_OK = 200;
    private static final int STATUS_CODE_CREATED = 201;
    private static final int STATUS_CODE_BAD_REQUEST = 400;
    private static final int STATUS_CODE_NOT_FOUND = 404;
    private static final int STATUS_CODE_METHOD_NOT_ALLOWED = 405;
    private static final int STATUS_CODE_INTERNAL_ERROR = 500;

    private static final MongoClient MONGO_CLIENT = createMongoClient();
    private static final MongoDatabase MONGO_DATABASE = createMongoDatabase(MONGO_CLIENT);
    private static final MongoCollection<Document> RIDES_COLLECTION = createRidesCollection(MONGO_DATABASE);

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

    private static MongoCollection<Document> createRidesCollection(MongoDatabase database) {
        String collectionName = System.getenv(ENV_MONGO_COLLECTION);
        if (collectionName == null || collectionName.isBlank()) {
            throw new IllegalStateException("Missing environment variable: " + ENV_MONGO_COLLECTION);
        }
        return database.getCollection(collectionName);
    }

    private static class CreateRideRequest {
        private String driver;

        String getDriver() {
            return driver;
        }
    }

    private static class RideUpdateRequest {
        private String id;
        private boolean available;
        private String passengerId;

        String getId() {
            return id;
        }

        boolean isAvailable() {
            return available;
        }

        String getPassengerId() {
            return passengerId;
        }
    }

    @SuppressWarnings("unused")
    private static class RideSummary {
        private final String id;
        private final String driver;
        private final boolean available;
        private final String passengerId;

        RideSummary(Document source) {
            this.id = source.getString(FIELD_ID);
            this.driver = source.getString(FIELD_DRIVER);
            this.available = source.getBoolean(FIELD_AVAILABLE, true);
            this.passengerId = source.getString(FIELD_PASSENGER_ID);
        }
    }

    private String serializeRides() {
        ArrayList<RideSummary> summaries = new ArrayList<>();
        RIDES_COLLECTION.find().map(RideSummary::new).into(summaries);
        return gson.toJson(summaries);
    }

    private String serializeRide(Document document) {
        return gson.toJson(new RideSummary(document));
    }

    private Optional<Document> findRideById(String rideId) {
        if (rideId == null || rideId.isBlank()) {
            return Optional.empty();
        }
        Document document = RIDES_COLLECTION.find(Filters.eq(FIELD_ID, rideId)).first();
        return Optional.ofNullable(document);
    }

    private APIGatewayProxyResponseEvent createRide(APIGatewayProxyRequestEvent input) {
        CreateRideRequest request = gson.fromJson(input.getBody(), CreateRideRequest.class);
        if (request == null || request.getDriver() == null || request.getDriver().isBlank()) {
            return buildErrorResponse(STATUS_CODE_BAD_REQUEST, MESSAGE_INVALID_BODY);
        }
        String rideId = RIDE_ID_PREFIX + UUID.randomUUID();
        Document document = new Document()
                .append(FIELD_ID, rideId)
                .append(FIELD_DRIVER, request.getDriver())
                .append(FIELD_AVAILABLE, true)
                .append(FIELD_PASSENGER_ID, null);
        RIDES_COLLECTION.insertOne(document);
        APIGatewayProxyResponseEvent responseEvent = new APIGatewayProxyResponseEvent();
        responseEvent.setStatusCode(STATUS_CODE_CREATED);
        responseEvent.setBody(serializeRide(document));
        return responseEvent;
    }

    private APIGatewayProxyResponseEvent updateRide(APIGatewayProxyRequestEvent input) {
        RideUpdateRequest request = gson.fromJson(input.getBody(), RideUpdateRequest.class);
        if (request == null || request.getId() == null || request.getId().isBlank()) {
            return buildErrorResponse(STATUS_CODE_BAD_REQUEST, MESSAGE_INVALID_BODY);
        }
        UpdateResult updateResult = RIDES_COLLECTION.updateOne(
                Filters.eq(FIELD_ID, request.getId()),
                Updates.combine(
                        Updates.set(FIELD_AVAILABLE, request.isAvailable()),
                        Updates.set(FIELD_PASSENGER_ID, request.getPassengerId())
                )
        );
        if (updateResult.getMatchedCount() == 0) {
            return buildErrorResponse(STATUS_CODE_NOT_FOUND, MESSAGE_RIDE_NOT_FOUND);
        }
        Optional<Document> ride = findRideById(request.getId());
        if (ride.isEmpty()) {
            return buildErrorResponse(STATUS_CODE_NOT_FOUND, MESSAGE_RIDE_NOT_FOUND);
        }
        APIGatewayProxyResponseEvent responseEvent = new APIGatewayProxyResponseEvent();
        responseEvent.setStatusCode(STATUS_CODE_OK);
        responseEvent.setBody(serializeRide(ride.get()));
        return responseEvent;
    }

    private APIGatewayProxyResponseEvent buildGetResponse(APIGatewayProxyRequestEvent input) {
        Map<String, String> parameters = input.getQueryStringParameters();
        if (parameters != null && parameters.containsKey(QUERY_PARAM_RIDE_ID)) {
            String rideId = parameters.get(QUERY_PARAM_RIDE_ID);
            if (rideId == null || rideId.isBlank()) {
                return buildErrorResponse(STATUS_CODE_BAD_REQUEST, MESSAGE_INVALID_BODY);
            }
            Optional<Document> ride = findRideById(rideId);
            if (ride.isEmpty()) {
                return buildErrorResponse(STATUS_CODE_NOT_FOUND, MESSAGE_RIDE_NOT_FOUND);
            }
            APIGatewayProxyResponseEvent responseEvent = new APIGatewayProxyResponseEvent();
            responseEvent.setStatusCode(STATUS_CODE_OK);
            responseEvent.setBody(serializeRide(ride.get()));
            return responseEvent;
        }
        APIGatewayProxyResponseEvent responseEvent = new APIGatewayProxyResponseEvent();
        responseEvent.setStatusCode(STATUS_CODE_OK);
        responseEvent.setBody(serializeRides());
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
                return createRide(input);
            }
            if (HTTP_METHOD_PUT.equals(method)) {
                return updateRide(input);
            }
            return buildErrorResponse(STATUS_CODE_METHOD_NOT_ALLOWED, MESSAGE_METHOD_NOT_ALLOWED);
        } catch (RuntimeException exception) {
            if (context != null && context.getLogger() != null) {
                context.getLogger().log(exception.getMessage());
            }
            return buildErrorResponse(STATUS_CODE_INTERNAL_ERROR, MESSAGE_INTERNAL_ERROR);
        }
    }
}