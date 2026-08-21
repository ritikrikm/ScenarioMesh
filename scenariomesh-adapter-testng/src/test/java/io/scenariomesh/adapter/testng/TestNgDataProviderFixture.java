package io.scenariomesh.adapter.testng;

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

public final class TestNgDataProviderFixture {
    @DataProvider(name = "rows")
    public Object[][] rows() {
        return new Object[][]{{"a"}, {"b"}};
    }

    @Test(dataProvider = "rows")
    public void parameterized(String value) {
        // fixture only
    }
}
