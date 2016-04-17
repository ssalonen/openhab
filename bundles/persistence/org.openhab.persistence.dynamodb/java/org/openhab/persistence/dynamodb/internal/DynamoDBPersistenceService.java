/**
 * Copyright (c) 2010-2016, openHAB.org and others.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.openhab.persistence.dynamodb.internal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.openhab.core.items.Item;
import org.openhab.core.items.ItemRegistry;
import org.openhab.core.library.types.HSBType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.OpenClosedType;
import org.openhab.core.library.types.PercentType;
import org.openhab.core.persistence.FilterCriteria;
import org.openhab.core.persistence.HistoricItem;
import org.openhab.core.persistence.PersistenceService;
import org.openhab.core.persistence.QueryablePersistenceService;
import org.openhab.core.types.State;
import org.openhab.core.types.UnDefType;
import org.osgi.framework.BundleContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.amazonaws.AmazonClientException;
import com.amazonaws.services.dynamodbv2.datamodeling.DynamoDBMapper;
import com.amazonaws.services.dynamodbv2.datamodeling.DynamoDBMapperConfig;
import com.amazonaws.services.dynamodbv2.model.CreateTableRequest;
import com.amazonaws.services.dynamodbv2.model.GlobalSecondaryIndex;
import com.amazonaws.services.dynamodbv2.model.ProvisionedThroughput;
import com.amazonaws.services.dynamodbv2.model.ResourceNotFoundException;
import com.amazonaws.services.dynamodbv2.model.TableDescription;
import com.amazonaws.services.dynamodbv2.model.TableStatus;

/**
 * This is the implementation of the DynamoDB {@link PersistenceService}. It persists item values
 * using the <a href="https://aws.amazon.com/dynamodb/">Amazon DynamoDB</a> time series database. The states (
 * {@link State}) of an {@link Item} are persisted in a time series with names equal to the name of
 * the item. All values are stored using integers or doubles, {@link OnOffType} and
 * {@link OpenClosedType} are stored using 0 or 1.
 *
 * The default database name is "openhab"
 *
 */
public class DynamoDBPersistenceService implements QueryablePersistenceService {

    private ItemRegistry itemRegistry;
    private DynamoDBClient db;
    private static final Logger logger = LoggerFactory.getLogger(DynamoDBPersistenceService.class);
    private boolean isProperlyConfigured;
    private DynamoDBConfig dbConfig;
    private DynamoDBTableNameResolver tableNameResolver;

    public void setItemRegistry(ItemRegistry itemRegistry) {
        this.itemRegistry = itemRegistry;
    }

    public void unsetItemRegistry(ItemRegistry itemRegistry) {
        this.itemRegistry = null;
    }

    public void activate(final BundleContext bundleContext, final Map<String, Object> config) {
        logger.debug("dynamodb persistence service activated");
        resetClient();
        try {
            dbConfig = DynamoDBConfig.fromConfig(config);
            tableNameResolver = new DynamoDBTableNameResolver(dbConfig.getTablePrefix());
        } catch (Exception e) {
            logger.error("Error with configuration: {}", e);
        }
        try {
            db = new DynamoDBClient(dbConfig);
            isProperlyConfigured = true;

            if (!checkConnection()) {
                logger.error("dynamodb: database connection does not work for now, will retry to use the database.");
            }
        } catch (Exception e) {
            logger.error("Error constructing dynamodb client", e);
        }
    }

    public void deactivate() {
        logger.debug("dynamodb persistence service deactivated");
        resetClient();
    }

    private boolean checkConnection() {
        if (db == null) {
            return false;
        }
        return db.checkConnection();
    }

    private synchronized boolean createTable(DynamoDBMapper mapper, Class<?> dtoClass) {
        if (db == null) {
            return false;
        }
        String tableName;
        try {
            ProvisionedThroughput provisionedThroughput = new ProvisionedThroughput(dbConfig.getReadCapacityUnits(),
                    dbConfig.getWriteCapacityUnits());
            CreateTableRequest request = mapper.generateCreateTableRequest(dtoClass);
            request.setProvisionedThroughput(provisionedThroughput);
            if (request.getGlobalSecondaryIndexes() != null) {
                for (GlobalSecondaryIndex index : request.getGlobalSecondaryIndexes()) {
                    index.setProvisionedThroughput(provisionedThroughput);
                }
            }
            tableName = request.getTableName();
            try {
                db.getDynamoClient().describeTable(tableName);
            } catch (ResourceNotFoundException e) {
                // No table present, continue with creation
                db.getDynamoClient().createTable(request);
            } catch (AmazonClientException e) {
                logger.error("Exception when creation table (descibe): {}", e.getMessage());
                return false;
            }

            // table found or just created, wait
            return waitForTableToBecomeActive(tableName);

        } catch (AmazonClientException e) {
            logger.error("Exception when creation table: {}", e.getMessage());
            return false;
        }

    }

    private boolean waitForTableToBecomeActive(String tableName) {
        try {
            logger.debug("Checking if table '{}' is created...", tableName);
            TableDescription tableDescription = db.getDynamoDB().getTable(tableName).waitForActiveOrDelete();
            boolean success = TableStatus.ACTIVE.equals(TableStatus.fromValue(tableDescription.getTableStatus()));
            if (success) {
                logger.info("Creation of table '{}' successfull, table is now {}", tableName,
                        tableDescription.getTableStatus());
            } else {
                logger.warn("Giving up table '{}' creation, most recent status was {}", tableName,
                        tableDescription.getTableStatus());
            }
            return success;
        } catch (AmazonClientException e) {
            logger.error("Exception when checking table status (describe): {}", e.getMessage());
            return false;
        } catch (InterruptedException e) {
            logger.error("Interrupted while trying to check table status: {}", e.getMessage());
            return false;
        }
    }

    private void resetClient() {
        if (db == null) {
            return;
        }
        db.shutdown();
        db = null;
        dbConfig = null;
        tableNameResolver = null;
        isProperlyConfigured = false;
    }

    private DynamoDBMapper getDBMapper(String tableName) {
        try {
            DynamoDBMapperConfig mapperConfig = new DynamoDBMapperConfig(
                    new DynamoDBMapperConfig.TableNameOverride(tableName));
            return new DynamoDBMapper(db.getDynamoClient(), mapperConfig);
        } catch (AmazonClientException e) {
            logger.error("Error getting db mapper");
            throw e;
        }
    }

    @Override
    public String getName() {
        return "dynamodb";
    }

    @Override
    public void store(Item item) {
        store(item, null);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void store(Item item, String alias) {
        if (item.getState() instanceof UnDefType) {
            logger.debug("Undefined item state received. Not storing item.");
            return;
        }
        if (!isProperlyConfigured) {
            logger.warn("Configuration for dynamodb not yet loaded or broken. Not storing item.");
            return;
        }
        if (!checkConnection()) {
            logger.warn("DynamoDB not connected. Not storing item.");
            return;
        }
        String realName = item.getName();
        String name = (alias != null) ? alias : realName;
        Date time = new Date(System.currentTimeMillis());

        State state = null;
        if (item.getAcceptedCommandTypes().contains(HSBType.class)) {
            state = item.getStateAs(HSBType.class);
            logger.trace("Tried to get item as {}, state is {}", HSBType.class, state.toString());
        } else if (item.getAcceptedDataTypes().contains(PercentType.class)) {
            state = item.getStateAs(PercentType.class);
            logger.trace("Tried to get item as {}, state is {}", PercentType.class, state.toString());
        } else {
            // All other items should return the best format by default
            state = item.getState();
            logger.trace("Tried to get item from item class {}, state is {}", item.getClass(), state.toString());
        }
        AbstractDynamoItem<?> dynamoItem = AbstractDynamoItem.fromState(name, state, time);
        DynamoDBMapper mapper = getDBMapper(tableNameResolver.fromItem(dynamoItem));

        if (!createTable(mapper, dynamoItem.getClass())) {
            logger.warn("Table creation failed. Not storing item");
        }

        try {
            logger.debug("storing {} in dynamo. Serialized value {}. Original Item: {}", name, state, item);
            mapper.save(dynamoItem);
            logger.debug("Sucessfully stored item {}", item);
        } catch (AmazonClientException e) {
            logger.error("Error storing object to dynamo");
        }

    }

    @Override
    public Iterable<HistoricItem> query(FilterCriteria filter) {
        logger.debug("got a query");
        if (!isProperlyConfigured) {
            logger.warn("Configuration for dynamodb not yet loaded or broken. Not storing item.");
            return Collections.emptyList();
        }
        if (!checkConnection()) {
            logger.warn("DynamoDB not connected. Not storing item.");
            return Collections.emptyList();
        }

        List<HistoricItem> historicItems = new ArrayList<HistoricItem>();

        // operator + item value
        // begin time.getTime() == millis
        // end
        // - sort, paging,

        // for (int i = 0; i < valuess.size(); i++) {
        // Double rawTime = (Double) valuess.get(i).get(timestampColumn);
        // Date time = new Date(rawTime.longValue());
        // State value = objectToState(valuess.get(i).get(valueColumn), historicItemName);
        // logger.trace("adding historic item {}: time {} value {}", historicItemName, time, value);
        // historicItems.add(new InfluxdbItem(historicItemName, value, time));
        // }
        return historicItems;
    }

}
