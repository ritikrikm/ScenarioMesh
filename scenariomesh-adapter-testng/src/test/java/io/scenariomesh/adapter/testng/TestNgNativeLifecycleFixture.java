package io.scenariomesh.adapter.testng;

import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.concurrent.atomic.AtomicInteger;

public final class TestNgNativeLifecycleFixture {
    static final AtomicInteger beforeClass = new AtomicInteger();
    static final AtomicInteger tests = new AtomicInteger();

    @BeforeClass public void beforeClass() { beforeClass.incrementAndGet(); }
    @Test public void first() { tests.incrementAndGet(); }
    @Test public void second() { tests.incrementAndGet(); }
}
