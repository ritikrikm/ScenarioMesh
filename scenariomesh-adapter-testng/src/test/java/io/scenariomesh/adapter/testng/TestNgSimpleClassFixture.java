package io.scenariomesh.adapter.testng;

import org.testng.annotations.Test;

public class TestNgSimpleClassFixture {
    @Test(groups = {"smoke", "tier$1"}) public void first() { }
    @Test(groups = {"regression"}) public void second() { }
}
