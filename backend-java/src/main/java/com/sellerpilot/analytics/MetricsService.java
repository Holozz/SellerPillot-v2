package com.sellerpilot.analytics;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import io.micrometer.core.instrument.MeterRegistry;
import com.sellerpilot.common.ApiException;
import com.sellerpilot.common.Rows;
import com.sellerpilot.store.StoreAccess;

@Service
public class MetricsService {
    private final StoreAccess access;private final MetricsMapper mapper;private final TransactionTemplate snapshot;
    private final Semaphore permits;private final MeterRegistry metrics;private final MetricsCache cache;private final boolean cacheEnabled;
    public MetricsService(StoreAccess access,MetricsMapper mapper,PlatformTransactionManager transactions,
                          @Value("${sellerpilot.cache.aggregation-permits}")int permits,MeterRegistry metrics,
                          MetricsCache cache,@Value("${sellerpilot.cache.enabled}")boolean cacheEnabled){
        this.access=access;this.mapper=mapper;this.permits=new Semaphore(permits);this.metrics=metrics;
        this.cache=cache;this.cacheEnabled=cacheEnabled;
        snapshot=new TransactionTemplate(transactions);snapshot.setReadOnly(true);
        snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        snapshot.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);snapshot.setTimeout(8);
    }
    public Map<String,Object> query(long user,long sid,QuerySpec body){
        QuerySpec.Normalized query=body.normalize();
        return cacheEnabled?cache.query(sid,query,version(user,sid),()->aggregate(user,sid,query)):aggregate(user,sid,query);
    }
    public long version(long user,long sid){return snapshot.execute(status->Rows.number(access.require(user,sid,"read",false),"data_version"));}
    public Map<String,Object> aggregate(long user,long sid,QuerySpec.Normalized query){
        if(!permits.tryAcquire()){metrics.counter("sellerpilot.aggregate.rejected").increment();throw new ApiException(503,"经营查询繁忙，请稍后重试");}
        long started=System.nanoTime();
        try{return snapshot.execute(status->{
            Map<String,Object> store=access.require(user,sid,"read",false);
            metrics.counter("sellerpilot.aggregate.sql").increment();
            Map<String,Object> result=new LinkedHashMap<>();
            result.put("kind","fact_card");result.put("store_id",sid);result.put("data_version",Rows.number(store,"data_version"));
            result.put("schema_version",1);result.put("as_of",Instant.now().toString());result.put("interval","[start,end)");
            result.put("sales_definition","net_sales = gross_sales - refund_amount; metric sales uses net_sales");
            result.put("query_spec",query.asMap());result.put("rows",mapper.query(sid,query).stream().map(Rows::json).toList());result.put("cache_hit",false);
            return result;
        });}finally{permits.release();metrics.timer("sellerpilot.aggregate.seconds").record(System.nanoTime()-started,java.util.concurrent.TimeUnit.NANOSECONDS);}
    }
}
