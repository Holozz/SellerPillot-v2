package com.sellerpilot.analytics;

import static org.junit.jupiter.api.Assertions.*;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import com.sellerpilot.common.ApiException;

class QuerySpecTest {
    @Test void equivalentRequestsShareCanonicalKey(){
        QuerySpec a=new QuerySpec("sales","product",OffsetDateTime.parse("2026-10-01T08:00:00+08:00"),OffsetDateTime.parse("2026-10-02T08:00:00+08:00"),List.of("shipped","paid","paid"),20,null);
        QuerySpec b=new QuerySpec("sales","product",OffsetDateTime.parse("2026-10-01T00:00:00Z"),OffsetDateTime.parse("2026-10-02T00:00:00Z"),List.of("paid","shipped"),20,null);
        assertEquals(a.normalize().canonical(),b.normalize().canonical());assertEquals(a.normalize().hash(),b.normalize().hash());
    }
    @Test void rejectsUncomputableMetric(){assertThrows(ApiException.class,()->new QuerySpec("profit",null,null,null,null,null,null).normalize());}
    @Test void requiresExplicitInterval(){assertThrows(ApiException.class,()->new QuerySpec("sales",null,null,null,null,null,null).normalize());}
}
