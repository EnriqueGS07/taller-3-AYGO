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
import org.bson.conversions.Bson;

import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public class Drivers implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {
    private static final String HTTP_METHOD_GET = "GET";
    private static final String HTTP_METHOD_POST = "POST";
    private static final String HTTP_METHOD_PUT = "PUT";
    private static final String MESSAGE_METHOD_NOT_ALLOWED = "Method not allowed";
    private static final String MESSAGE_DRIVER_NOT_FOUND = "Driver not found";
    private static final String MESSAGE_INVALID_BODY = "Invalid request body";
    private static final String MESSAGE_INTERNAL_ERROR = "Internal server error";
    private static final String QUERY_PARAM_DRIVER_ID = "id";

    private static final String DRIVER_ID_PREFIX = "d_";

    private static final String ENV_MONGO_URI = "MONGO_URI";
    private static final String ENV_MONGO_DB = "MONGO_DB";
    private static final String ENV_MONGO_COLLECTION = "MONGO_COLLECTION";

    private static final String FIELD_ID = "id";
    private static final String FIELD_NAME = "name";
    private static final String FIELD_TRAVELING = "traveling";
    private static final String FIELD_TRAVEL = "travel";
    private static final String FIELD_BUSY = "busy";
    private static final String FIELD_CAR = "car";

    private static final MongoClient MONGO_CLIENT = createMongoClient();
    private static final MongoDatabase MONGO_DATABASE = createMongoDatabase(MONGO_CLIENT);
    private static final MongoCollection<Document> DRIVERS_COLLECTION = createDriversCollection(MONGO_DATABASE);

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

    private static MongoCollection<Document> createDriversCollection(MongoDatabase database) {
        String collectionName = System.getenv(ENV_MONGO_COLLECTION);
        if (collectionName == null || collectionName.isBlank()) {
            throw new IllegalStateException("Missing environment variable: " + ENV_MONGO_COLLECTION);
        }
        return database.getCollection(collectionName);
    }

    private static class CreateDriverRequest {
        private String name;
        private String car;

        String getName() {
            return name;
        }

        String getCar() {
            return car;
        }
    }

    private static class TravelUpdateRequest {
        private String id;
        private boolean traveling;
        private String rideId;
        private Boolean busy;
        private String car;

        String getId() {
            return id;
        }

        boolean isTraveling() {
            return traveling;
        }

        String getRideId() {
            return rideId;
        }

        Boolean getBusy() {
            return busy;
        }

        String getCar() {
            return car;
        }
    }

    @SuppressWarnings("unused")
    private static class DriverSummary {
        private final String id;
        private final String name;
        private final boolean traveling;
        private final String travel;
        private final boolean busy;
        private final String car;

        DriverSummary(Document source) {
            this.id = source.getString(FIELD_ID);
            this.name = source.getString(FIELD_NAME);
            this.traveling = source.getBoolean(FIELD_TRAVELING, false);
            this.travel = source.getString(FIELD_TRAVEL);
            this.busy = source.getBoolean(FIELD_BUSY, false);
            this.car = source.getString(FIELD_CAR);
        }
    }

    private String serializeDrivers() {
        ArrayList<DriverSummary> summaries = new ArrayList<>();
        DRIVERS_COLLECTION.find().map(DriverSummary::new).into(summaries);
        return gson.toJson(summaries);
    }

    private String serializeDriver(Document document) {
        return gson.toJson(new DriverSummary(document));
    }

    private Optional<Document> findDriverById(String driverId) {
        if (driverId == null || driverId.isBlank()) {
            return Optional.empty();
        }
        Document document = DRIVERS_COLLECTION.find(Filters.eq(FIELD_ID, driverId)).first();
        return Optional.ofNullable(document);
    }

    private APIGatewayProxyResponseEvent createDriver(APIGatewayProxyRequestEvent input) {
        CreateDriverRequest request = gson.fromJson(input.getBody(), CreateDriverRequest.class);
        if (request == null || request.getName() == null || request.getName().isBlank()) {
            return buildErrorResponse(400, MESSAGE_INVALID_BODY);
        }
        String driverId = DRIVER_ID_PREFIX + UUID.randomUUID();
        Document document = new Document()
                .append(FIELD_ID, driverId)
                .append(FIELD_NAME, request.getName())
                .append(FIELD_TRAVELING, false)
                .append(FIELD_TRAVEL, null)
                .append(FIELD_BUSY, false)
                .append(FIELD_CAR, request.getCar());
        DRIVERS_COLLECTION.insertOne(document);
        APIGatewayProxyResponseEvent responseEvent = new APIGatewayProxyResponseEvent();
        responseEvent.setStatusCode(201);
        responseEvent.setBody(serializeDriver(document));
        return responseEvent;
    }

    private APIGatewayProxyResponseEvent updateTravel(APIGatewayProxyRequestEvent input) {
        TravelUpdateRequest request = gson.fromJson(input.getBody(), TravelUpdateRequest.class);
        if (request == null || request.getId() == null || request.getId().isBlank()) {
            return buildErrorResponse(400, MESSAGE_INVALID_BODY);
        }

        ArrayList<Bson> updates = new ArrayList<>();
        updates.add(Updates.set(FIELD_TRAVELING, request.isTraveling()));
        updates.add(Updates.set(FIELD_TRAVEL, request.getRideId()));
        if (request.getBusy() != null) {
            updates.add(Updates.set(FIELD_BUSY, request.getBusy()));
        }
        if (request.getCar() != null) {
            updates.add(Updates.set(FIELD_CAR, request.getCar()));
        }

        UpdateResult updateResult = DRIVERS_COLLECTION.updateOne(
                Filters.eq(FIELD_ID, request.getId()),
                Updates.combine(updates)
        );

        if (updateResult.getMatchedCount() == 0) {
            return buildErrorResponse(404, MESSAGE_DRIVER_NOT_FOUND);
        }

        Optional<Document> driver = findDriverById(request.getId());
        if (driver.isEmpty()) {
            return buildErrorResponse(404, MESSAGE_DRIVER_NOT_FOUND);
        }

        APIGatewayProxyResponseEvent responseEvent = new APIGatewayProxyResponseEvent();
        responseEvent.setStatusCode(200);
        responseEvent.setBody(serializeDriver(driver.get()));
        return responseEvent;
    }

    private APIGatewayProxyResponseEvent buildGetResponse(APIGatewayProxyRequestEvent input) {
        Map<String, String> parameters = input.getQueryStringParameters();
        if (parameters != null && parameters.containsKey(QUERY_PARAM_DRIVER_ID)) {
            String driverId = parameters.get(QUERY_PARAM_DRIVER_ID);
            if (driverId == null || driverId.isBlank()) {
                return buildErrorResponse(400, MESSAGE_INVALID_BODY);
            }
            Optional<Document> driver = findDriverById(driverId);
            if (driver.isEmpty()) {
                return buildErrorResponse(404, MESSAGE_DRIVER_NOT_FOUND);
            }
            APIGatewayProxyResponseEvent responseEvent = new APIGatewayProxyResponseEvent();
            responseEvent.setStatusCode(200);
            responseEvent.setBody(serializeDriver(driver.get()));
            return responseEvent;
        }
        APIGatewayProxyResponseEvent responseEvent = new APIGatewayProxyResponseEvent();
        responseEvent.setStatusCode(200);
        responseEvent.setBody(serializeDrivers());
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
                return createDriver(input);
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