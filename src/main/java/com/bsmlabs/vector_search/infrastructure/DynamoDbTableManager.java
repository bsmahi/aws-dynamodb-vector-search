package com.bsmlabs.vector_search.infrastructure;

import com.bsmlabs.vector_search.config.AppProperties;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import static java.lang.System.out;

@Component
public class DynamoDbTableManager {

    private final DynamoDbClient dynamodb;
    private final AppProperties config;

    public DynamoDbTableManager(DynamoDbClient dynamodb, AppProperties config) {
        this.dynamodb = dynamodb;
        this.config = config;
    }

    public void createTableIfNotExists() {
        try {
            var request = CreateTableRequest.builder()
                    .tableName(config.dynamoDb().tableName())
                    .attributeDefinitions(
                            AttributeDefinition.builder()
                                    .attributeName("paper_id")
                                    .attributeType(ScalarAttributeType.S)
                                    .build()
                    )
                    .keySchema(
                            KeySchemaElement.builder()
                                    .attributeName("paper_id")
                                    .keyType(KeyType.HASH)
                                    .build()
                    )
                    .billingMode(BillingMode.PAY_PER_REQUEST)
                    .build();

            dynamodb.createTable(request);
            out.println("Table creation requested for " + config.dynamoDb().tableName());
        } catch (ResourceInUseException e) {
            out.println("Table already exists. Proceeding...");
        }
    }

    public void waitUntilActive() {
        var request = DescribeTableRequest.builder()
                .tableName(config.dynamoDb().tableName())
                .build();

        try (var waiter = dynamodb.waiter()) {
            var response = waiter.waitUntilTableExists(request)
                    .matched()
                    .response()
                    .orElseThrow(() -> new IllegalStateException(
                            "DynamoDB table waiter completed without a response"));
            var status = response.table().tableStatus();
            out.printf("Table Status: %s%n", status);

            if (TableStatus.ACTIVE.equals(status)) {
                out.println("DynamoDB Table is ACTIVE.");
            }
        }
    }
}
