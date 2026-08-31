package example;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

class TaggedTest {
    @Test @Tag("smoke") void smokeRuns() {}
    @Test @Tag("api") void apiRuns() {}
    @Test @Tag("smoke") @Tag("slow") void excludedTagWins() {
        throw new AssertionError("excludedGroups must veto an included tag");
    }
    @Test @Tag("other") void unmatchedTagDoesNotRun() {
        throw new AssertionError("groups tag expression must filter this test");
    }
}
