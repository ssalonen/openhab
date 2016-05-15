package org.openhab.persistence.dynamodb.internal;

import static org.junit.Assert.assertEquals;

import java.math.BigDecimal;
import java.util.Date;

import org.junit.BeforeClass;
import org.openhab.core.library.items.NumberItem;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.types.State;

public class NumberItemIntegrationTest extends TwoItemIntegrationTest {

    private static final String name = "number";
    // On purpose we have super accurate number here (testing limits of aws)
    private static final DecimalType state1 = new DecimalType(new BigDecimal(
            "-3.193490838904389298049802398048923849032809483209483209482309840239840932840932849083094809483"));
    private static final DecimalType state2 = new DecimalType(600.9123);
    private static final DecimalType stateBetween = new DecimalType(500);

    @BeforeClass
    public static void storeData() throws InterruptedException {
        NumberItem item = (NumberItem) items.get(name);

        item.setState(state1);

        beforeStore = new Date();
        Thread.sleep(10);
        service.store(item);
        afterStore1 = new Date();
        Thread.sleep(10);
        item.setState(state2);
        service.store(item);
        Thread.sleep(10);
        afterStore2 = new Date();

        logger.info("Created item between {} and {}", AbstractDynamoDBItem.DATEFORMATTER.format(beforeStore),
                AbstractDynamoDBItem.DATEFORMATTER.format(afterStore1));
    }

    @Override
    protected String getItemName() {
        return name;
    }

    @Override
    protected State getFirstItemState() {
        return state1;
    }

    @Override
    protected State getSecondItemState() {
        return state2;
    }

    @Override
    protected State getQueryItemStateBetween() {
        return stateBetween;
    }

    /**
     * Use relaxed state comparison due to numerical rounding. See also DynamoDBBigDecimalItem.loseDigits
     */
    @Override
    protected void assertStateEquals(State expected, State actual) {
        BigDecimal expectedDecimal = ((DecimalType) expected).toBigDecimal();
        BigDecimal actualDecimal = ((DecimalType) expected).toBigDecimal();
        assertEquals(DynamoDBBigDecimalItem.loseDigits(expectedDecimal),
                DynamoDBBigDecimalItem.loseDigits(actualDecimal));
    }

}
