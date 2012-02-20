package com.codeslap.persistence;

import com.codeslap.robolectric.RobolectricSimpleRunner;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * @author cristian
 */
@RunWith(RobolectricSimpleRunner.class)
public class TestInsertion extends SqliteTest {

    @Test
    public void testSingleInsertion() {
        // create a simple object
        ExampleAutoincrement foo = new ExampleAutoincrement();
        foo.name = "Foo Bar";
        foo.number = 111;
        foo.decimal = 222f;
        foo.bool = true;

        // insert it into the database
        Object id = getAdapter().store(foo);

        // it should have inserted in the first record
        assertTrue(id instanceof Long);
        foo.id = ((Long) id).longValue();
        assertEquals(1L, ((Long) id).longValue());

        // if we retrieve all elements, it should be there in the first record
        List<ExampleAutoincrement> all = getAdapter().findAll(ExampleAutoincrement.class);
        assertEquals(1, all.size());
        assertEquals(foo, all.get(0));
    }

    @Test
    public void testCollectionInsertion() {
        List<ExampleAutoincrement> collection = new ArrayList<ExampleAutoincrement>();
        Random random = new Random();
        for (int i = 0; i < 100; i++) {
            ExampleAutoincrement foo = new ExampleAutoincrement();
            foo.name = "Foo Bar " + random.nextInt();
            foo.number = random.nextInt();
            foo.decimal = random.nextFloat();
            foo.bool = random.nextBoolean();
            collection.add(foo);
        }
        getAdapter().storeCollection(collection, null);

        // it should have stored all items
        assertEquals(collection.size(), getAdapter().count(ExampleAutoincrement.class));

        // now let's see if it stored everything
        for (ExampleAutoincrement exampleAutoincrement : collection) {
            ExampleAutoincrement found = getAdapter().findFirst(exampleAutoincrement);
            assertNotNull(found);
            exampleAutoincrement.id = found.id;
            assertEquals(exampleAutoincrement, found);
        }
    }
}