package org.openhab.persistence.dynamodb.internal;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.commons.lang.NotImplementedException;
import org.junit.BeforeClass;
import org.openhab.core.items.Item;
import org.openhab.core.items.ItemNotFoundException;
import org.openhab.core.items.ItemNotUniqueException;
import org.openhab.core.items.ItemRegistry;
import org.openhab.core.items.ItemRegistryChangeListener;
import org.openhab.core.library.items.DimmerItem;
import org.openhab.core.library.items.NumberItem;

import com.amazonaws.services.dynamodbv2.model.ResourceNotFoundException;

public class BaseIntegrationTest {

    protected static DynamoDBPersistenceService service;
    protected final static Map<String, Item> items = new HashMap<>();
    protected static DimmerItem dimmerItem;
    protected static NumberItem numberItem;

    static {
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "trace");
    }

    @BeforeClass
    public static void initService() throws InterruptedException {
        dimmerItem = new DimmerItem("dimmer");
        items.put("dimmer", dimmerItem);

        numberItem = new NumberItem("number");
        items.put("number", numberItem);

        service = new DynamoDBPersistenceService();
        service.setItemRegistry(new ItemRegistry() {

            @Override
            public void removeItemRegistryChangeListener(ItemRegistryChangeListener listener) {
                throw new NotImplementedException();

            }

            @Override
            public boolean isValidItemName(String itemName) {
                throw new NotImplementedException();
            }

            @Override
            public Collection<Item> getItems(String pattern) {
                throw new NotImplementedException();
            }

            @Override
            public Collection<Item> getItems() {
                throw new NotImplementedException();
            }

            @Override
            public Item getItemByPattern(String name) throws ItemNotFoundException, ItemNotUniqueException {
                throw new NotImplementedException();
            }

            @Override
            public Item getItem(String name) throws ItemNotFoundException {
                Item item = items.get(name);
                if (item == null) {
                    throw new ItemNotFoundException(name);
                }
                return item;
            }

            @Override
            public void addItemRegistryChangeListener(ItemRegistryChangeListener listener) {
                throw new NotImplementedException();
            }
        });

        HashMap<String, Object> config = new HashMap<>();
        config.put("region", System.getProperty("DYNAMODBTEST_REGION"));
        config.put("accessKey", System.getProperty("DYNAMODBTEST_ACCESS"));
        config.put("secretKey", System.getProperty("DYNAMODBTEST_SECRET"));
        config.put("tablePrefix", "dynamodb-integration-tests-");

        for (Entry<String, Object> entry : config.entrySet()) {
            if (entry.getValue() == null) {
                throw new AssertionError(
                        String.format("Expecting %s to have value for integration tests", entry.getKey()));
            }
        }

        service.activate(null, config);

        // Clear data
        for (String table : new String[] { "dynamodb-integration-tests-bigdecimal",
                "dynamodb-integration-tests-string" }) {
            try {
                service.getDb().getDynamoClient().deleteTable(table);
                service.getDb().getDynamoDB().getTable(table).waitForDelete();
            } catch (ResourceNotFoundException e) {
            }
        }

    }

}
