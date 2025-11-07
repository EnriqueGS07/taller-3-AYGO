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
import com.mongodb.client.result.DeleteResult;
import com.mongodb.client.result.InsertOneResult;
import com.mongodb.client.result.UpdateResult;

import org.bson.Document;

import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;

public class Payments implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {
    private static final String HTTP_METHOD_GET = "GET";
    private static final String HTTP_METHOD_POST = "POST";
    private static final String HTTP_METHOD_PUT = "PUT";
    private static final String HTTP_METHOD_DELETE = "DELETE";
    private static final String MESSAGE_METHOD_NOT_ALLOWED = "Method not allowed";
    private static final String MESSAGE_PAYMENT_NOT_FOUND = "Payment not found";
    private static final String MESSAGE_INVALID_BODY = "Invalid request body";
    private static final String MESSAGE_INTERNAL_ERROR = "Internal server error";
    private static final String MESSAGE_DELETED = "Deleted payment";
    private static final String QUERY_PARAM_PAYMENT_ID = "id";

    private static final String PAYMENT_ID_PREFIX = "pay_";

    private static final String ENV_MONGO_URI = "MONGO_URI";
    private static final String ENV_MONGO_DB = "MONGO_DB";
    private static final String ENV_MONGO_COLLECTION = "MONGO_PAYMENTS_COLLECTION";

    private static final String FIELD_ID = "id";
    private static final String FIELD_USER_ID = "userId";
    private static final String FIELD_AMOUNT = "amount";
    private static final String FIELD_PROCESSED = "processed";
    private static final String FIELD_TRANSACTION_ID = "transactionId";
    private static final String FIELD_RIDE_ID = "rideId";

    private static final MongoClient MONGO_CLIENT = createMongoClient();
    private static final MongoDatabase MONGO_DATABASE = createMongoDatabase(MONGO_CLIENT);
    private static final MongoCollection<Document> PAYMENTS_COLLECTION = createPaymentsCollection(MONGO_DATABASE);

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

    private static MongoCollection<Document> createPaymentsCollection(MongoDatabase database) {
        String collectionName = System.getenv(ENV_MONGO_COLLECTION);
        if (collectionName == null || collectionName.isBlank()) {
            throw new IllegalStateException("Missing environment variable: " + ENV_MONGO_COLLECTION);
        }
        return database.getCollection(collectionName);
    }

    private static class PaymentCreationRequest {
        private String userId;
        private double amount;
        private String rideId;

        String getUserId() {
            return userId;
        }

        double getAmount() {
            return amount;
        }

        String getRideId() {
            return rideId;
        }
    }

    private static class PaymentUpdateRequest {
        private String id;
        private boolean processed;
        private String transactionId;
        private String rideId;
        private Double amount;

        String getId() {
            return id;
        }

        boolean isProcessed() {
            return processed;
        }

        String getTransactionId() {
            return transactionId;
        }

        String getRideId() {
            return rideId;
        }

        Double getAmount() {
            return amount;
        }
    }

    @SuppressWarnings("unused")
    private static class PaymentSummary {
        private final String id;
        private final String userId;
        private final double amount;
        private final boolean processed;
        private final String transactionId;
        private final String rideId;

        PaymentSummary(Document source) {
            this.id = source.getString(FIELD_ID);
            this.userId = source.getString(FIELD_USER_ID);
            this.amount = source.getDouble(FIELD_AMOUNT);
            this.processed = source.getBoolean(FIELD_PROCESSED, false);
            this.transactionId = source.getString(FIELD_TRANSACTION_ID);
            this.rideId = source.getString(FIELD_RIDE_ID);
        }
    }

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent input, Context context) {
        try {
            String method = input.getHttpMethod();
            if (HTTP_METHOD_GET.equals(method)) {
                return buildGetResponse(input);
            }
            if (HTTP_METHOD_POST.equals(method)) {
                return createPayment(input);
            }
            if (HTTP_METHOD_PUT.equals(method)) {
                return updatePayment(input);
            }
            if (HTTP_METHOD_DELETE.equals(method)) {
                return deletePayment(input);
            }
            return buildErrorResponse(405, MESSAGE_METHOD_NOT_ALLOWED);
        } catch (RuntimeException exception) {
            if (context != null && context.getLogger() != null) {
                context.getLogger().log(exception.getMessage());
            }
            return buildErrorResponse(500, MESSAGE_INTERNAL_ERROR);
        }
    }

    private APIGatewayProxyResponseEvent createPayment(APIGatewayProxyRequestEvent input) {
        PaymentCreationRequest request = gson.fromJson(input.getBody(), PaymentCreationRequest.class);
        if (!isValidCreationRequest(request)) {
            return buildErrorResponse(400, MESSAGE_INVALID_BODY);
        }
        String paymentId = PAYMENT_ID_PREFIX + java.util.UUID.randomUUID();
        Document document = new Document()
                .append(FIELD_ID, paymentId)
                .append(FIELD_USER_ID, request.getUserId())
                .append(FIELD_AMOUNT, request.getAmount())
                .append(FIELD_PROCESSED, Boolean.FALSE)
                .append(FIELD_TRANSACTION_ID, null)
                .append(FIELD_RIDE_ID, request.getRideId());
        InsertOneResult result = PAYMENTS_COLLECTION.insertOne(document);
        if (!result.wasAcknowledged()) {
            return buildErrorResponse(500, MESSAGE_INTERNAL_ERROR);
        }
        APIGatewayProxyResponseEvent responseEvent = new APIGatewayProxyResponseEvent();
        responseEvent.setStatusCode(201);
        responseEvent.setBody(serializePayment(document));
        return responseEvent;
    }

    private APIGatewayProxyResponseEvent updatePayment(APIGatewayProxyRequestEvent input) {
        PaymentUpdateRequest request = gson.fromJson(input.getBody(), PaymentUpdateRequest.class);
        if (!isValidUpdateRequest(request)) {
            return buildErrorResponse(400, MESSAGE_INVALID_BODY);
        }
        ArrayList<org.bson.conversions.Bson> updates = new ArrayList<>();
        updates.add(Updates.set(FIELD_PROCESSED, request.isProcessed()));
        updates.add(Updates.set(FIELD_TRANSACTION_ID, request.getTransactionId()));
        updates.add(Updates.set(FIELD_RIDE_ID, request.getRideId()));
        if (request.getAmount() != null) {
            updates.add(Updates.set(FIELD_AMOUNT, request.getAmount()));
        }
        UpdateResult updateResult = PAYMENTS_COLLECTION.updateOne(
                Filters.eq(FIELD_ID, request.getId()),
                Updates.combine(updates)
        );
        if (updateResult.getMatchedCount() == 0) {
            return buildErrorResponse(404, MESSAGE_PAYMENT_NOT_FOUND);
        }
        Optional<Document> payment = findPaymentById(request.getId());
        if (payment.isEmpty()) {
            return buildErrorResponse(404, MESSAGE_PAYMENT_NOT_FOUND);
        }
        APIGatewayProxyResponseEvent responseEvent = new APIGatewayProxyResponseEvent();
        responseEvent.setStatusCode(200);
        responseEvent.setBody(serializePayment(payment.get()));
        return responseEvent;
    }

    private APIGatewayProxyResponseEvent deletePayment(APIGatewayProxyRequestEvent input) {
        Map<String, String> parameters = input.getQueryStringParameters();
        if (parameters == null || !parameters.containsKey(QUERY_PARAM_PAYMENT_ID)) {
            return buildErrorResponse(400, MESSAGE_INVALID_BODY);
        }
        String paymentId = parameters.get(QUERY_PARAM_PAYMENT_ID);
        if (paymentId == null || paymentId.isBlank()) {
            return buildErrorResponse(400, MESSAGE_INVALID_BODY);
        }
        DeleteResult deleteResult = PAYMENTS_COLLECTION.deleteOne(Filters.eq(FIELD_ID, paymentId));
        if (deleteResult.getDeletedCount() == 0) {
            return buildErrorResponse(404, MESSAGE_PAYMENT_NOT_FOUND);
        }
        APIGatewayProxyResponseEvent responseEvent = new APIGatewayProxyResponseEvent();
        responseEvent.setStatusCode(200);
        responseEvent.setBody(MESSAGE_DELETED);
        return responseEvent;
    }

    private APIGatewayProxyResponseEvent buildGetResponse(APIGatewayProxyRequestEvent input) {
        Map<String, String> parameters = input.getQueryStringParameters();
        if (parameters != null && parameters.containsKey(QUERY_PARAM_PAYMENT_ID)) {
            String paymentId = parameters.get(QUERY_PARAM_PAYMENT_ID);
            if (paymentId == null || paymentId.isBlank()) {
                return buildErrorResponse(400, MESSAGE_INVALID_BODY);
            }
            Optional<Document> payment = findPaymentById(paymentId);
            if (payment.isEmpty()) {
                return buildErrorResponse(404, MESSAGE_PAYMENT_NOT_FOUND);
            }
            APIGatewayProxyResponseEvent responseEvent = new APIGatewayProxyResponseEvent();
            responseEvent.setStatusCode(200);
            responseEvent.setBody(serializePayment(payment.get()));
            return responseEvent;
        }
        APIGatewayProxyResponseEvent responseEvent = new APIGatewayProxyResponseEvent();
        responseEvent.setStatusCode(200);
        responseEvent.setBody(serializePayments());
        return responseEvent;
    }

    private Optional<Document> findPaymentById(String paymentId) {
        Document document = PAYMENTS_COLLECTION.find(Filters.eq(FIELD_ID, paymentId)).first();
        return Optional.ofNullable(document);
    }

    private boolean isValidCreationRequest(PaymentCreationRequest request) {
        if (request == null) {
            return false;
        }
        if (request.getUserId() == null || request.getUserId().isBlank()) {
            return false;
        }
        return request.getRideId() != null && !request.getRideId().isBlank();
    }

    private boolean isValidUpdateRequest(PaymentUpdateRequest request) {
        if (request == null) {
            return false;
        }
        if (request.getId() == null || request.getId().isBlank()) {
            return false;
        }
        if (request.getTransactionId() == null || request.getTransactionId().isBlank()) {
            return false;
        }
        return request.getRideId() != null && !request.getRideId().isBlank();
    }

    private String serializePayment(Document document) {
        return gson.toJson(new PaymentSummary(document));
    }

    private String serializePayments() {
        ArrayList<PaymentSummary> summaries = new ArrayList<>();
        PAYMENTS_COLLECTION.find().map(PaymentSummary::new).into(summaries);
        return gson.toJson(summaries);
    }

    private APIGatewayProxyResponseEvent buildErrorResponse(int statusCode, String message) {
        APIGatewayProxyResponseEvent responseEvent = new APIGatewayProxyResponseEvent();
        responseEvent.setStatusCode(statusCode);
        responseEvent.setBody(message);
        return responseEvent;
    }
}