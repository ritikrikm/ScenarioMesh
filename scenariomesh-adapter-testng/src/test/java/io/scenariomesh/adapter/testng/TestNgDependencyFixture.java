package io.scenariomesh.adapter.testng;

import org.testng.annotations.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public final class TestNgDependencyFixture {
    static final List<String> order = new CopyOnWriteArrayList<>();

    @Test public void first() { order.add("first"); }
    @Test(dependsOnMethods = "first") public void second() { order.add("second"); }
}
