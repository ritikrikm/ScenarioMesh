package io.scenariomesh.adapter.testng;

import org.testng.annotations.Factory;
import org.testng.annotations.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public final class TestNgFactoryFixture {
    @Factory public Object[] instances() {
        return new Object[]{new Product("a"), new Product("b")};
    }

    public static final class Product {
        static final List<String> values = new CopyOnWriteArrayList<>();
        private final String value;
        Product(String value) { this.value = value; }
        @Test public void test() { values.add(value); }
    }
}
