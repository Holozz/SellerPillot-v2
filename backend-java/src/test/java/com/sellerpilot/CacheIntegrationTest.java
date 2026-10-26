package com.sellerpilot;

import static org.junit.jupiter.api.Assertions.*;
import com.sellerpilot.analytics.*;
import com.sellerpilot.common.Rows;
import com.sellerpilot.store.StoreMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@EnabledIfEnvironmentVariable(named="JAVA_IT_MYSQL_URL",matches=".+")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties="sellerpilot.cache.enabled=true")
class CacheIntegrationTest {
    @DynamicPropertySource static void services(DynamicPropertyRegistry registry){
        String url=System.getenv("JAVA_IT_MYSQL_URL");
        if(url==null||!url.matches("jdbc:mysql://[^/]+/sellerpilot_java_test(?:\\?.*)?"))throw new IllegalArgumentException("只允许Java测试库");
        registry.add("spring.datasource.url",()->url);registry.add("spring.data.redis.database",()->9);
    }
    @Autowired JdbcTemplate db;@Autowired MetricsService service;@Autowired MetricsCache cache;
    @Autowired MetricsMapper mapper;@Autowired StoreMapper stores;@Autowired PlatformTransactionManager transactions;
    @Autowired StringRedisTemplate redis;@Autowired JsonMapper json;@Autowired MeterRegistry meters;
    long user,sid;
    QuerySpec query(){return new QuerySpec("sales","currency",OffsetDateTime.parse("2026-10-01T00:00:00Z"),OffsetDateTime.parse("2026-10-03T00:00:00Z"),null,20,null);}
    @BeforeEach void fixture(){
        for(String table:List.of("audit_logs","action_executions","action_proposals","import_batches","order_items","orders","products","store_memberships","stores","users"))db.update("DELETE FROM "+table);
        db.update("INSERT INTO users(username,password_hash) VALUES('cache-owner','unused')");user=db.queryForObject("SELECT id FROM users",Long.class);
        db.update("INSERT INTO stores(owner_id,name,platform) VALUES(?,'Cache fixture','amazon')",user);sid=db.queryForObject("SELECT id FROM stores",Long.class);
        db.update("INSERT INTO products(store_id,sku,title,price,currency) VALUES(?,'JAR-001','Jar',10,'USD')",sid);long pid=db.queryForObject("SELECT id FROM products",Long.class);
        db.update("INSERT INTO orders(store_id,external_order_id,ordered_at,status,currency) VALUES(?,'O1','2026-10-02','paid','USD')",sid);long oid=db.queryForObject("SELECT id FROM orders",Long.class);
        db.update("INSERT INTO order_items(store_id,order_id,product_id,line_no,quantity,unit_price,refund_amount) VALUES(?,?,?,1,1,10,0)",sid,oid,pid);
    }
    @Test void cacheHitAndWriteVersionVisibility(){
        assertEquals(false,service.query(user,sid,query()).get("cache_hit"));assertEquals(true,service.query(user,sid,query()).get("cache_hit"));
        new TransactionTemplate(transactions).executeWithoutResult(status->{db.update("UPDATE order_items SET quantity=2");stores.bump(sid);});
        Map<String,Object> result=service.query(user,sid,query());assertEquals(1L,Rows.number(result,"data_version"));assertEquals("20.00",((List<Map<String,Object>>)result.get("rows")).getFirst().get("value"));
    }
    @Test void rrKeepsSnapshotButRcCanMixOldVersionAndNewRows(){
        Map<String,Object> report=new LinkedHashMap<>();
        for(int isolation:List.of(TransactionDefinition.ISOLATION_REPEATABLE_READ,TransactionDefinition.ISOLATION_READ_COMMITTED)){
            db.update("UPDATE order_items SET quantity=1");db.update("UPDATE stores SET data_version=0");
            TransactionTemplate tx=new TransactionTemplate(transactions);tx.setReadOnly(true);tx.setIsolationLevel(isolation);
            Map<String,Object> sample=tx.execute(status->{
                long firstVersion=Rows.number(stores.get(sid),"data_version");
                CompletableFuture.runAsync(()->new TransactionTemplate(transactions).executeWithoutResult(write->{db.update("UPDATE order_items SET quantity=2");stores.bump(sid);})).join();
                return Map.of("version_before_write",firstVersion,"value_after_other_commit",Rows.json(mapper.query(sid,query().normalize()).getFirst()).get("value"));
            });
            assertEquals(isolation==TransactionDefinition.ISOLATION_REPEATABLE_READ?"10.00":"20.00",sample.get("value_after_other_commit"));
            report.put(isolation==TransactionDefinition.ISOLATION_REPEATABLE_READ?"RR":"RC",sample);
        }
        try{java.nio.file.Path out=java.nio.file.Path.of("../docs/verification/java/mysql-snapshot.json");java.nio.file.Files.createDirectories(out.getParent());java.nio.file.Files.writeString(out,json.writeValueAsString(report));}catch(java.io.IOException e){throw new RuntimeException(e);}
    }
    @Test void twoCacheInstancesCoalesceOneRebuildUnderNormalLease()throws Exception{
        MetricsCache other=new MetricsCache(redis,json,meters,120);AtomicInteger sqlCalls=new AtomicInteger();CountDownLatch go=new CountDownLatch(1);
        Supplier<Map<String,Object>> aggregate=()->{sqlCalls.incrementAndGet();try{Thread.sleep(150);}catch(InterruptedException e){Thread.currentThread().interrupt();}return service.aggregate(user,sid,query().normalize());};
        List<Future<Map<String,Object>>> work=new ArrayList<>();
        try(ExecutorService pool=Executors.newFixedThreadPool(12)){
            for(int i=0;i<12;i++){MetricsCache instance=i%2==0?cache:other;work.add(pool.submit(()->{go.await();return instance.query(sid,query().normalize(),0,aggregate);}));}
            go.countDown();for(Future<Map<String,Object>> item:work)assertEquals(0L,Rows.number(item.get(5,TimeUnit.SECONDS),"data_version"));
        }
        assertEquals(1,sqlCalls.get(),"正常租约内的两实例重建应该合并；不外推到租约过期和进程故障");
    }
    @Test void lateOldSnapshotCannotPolluteNewVersionCache()throws Exception{
        CountDownLatch snap=new CountDownLatch(1),release=new CountDownLatch(1);
        TransactionTemplate rr=new TransactionTemplate(transactions);rr.setReadOnly(true);rr.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        CompletableFuture<Map<String,Object>> old=CompletableFuture.supplyAsync(()->cache.query(sid,query().normalize(),0,()->rr.execute(status->{
            long version=Rows.number(stores.get(sid),"data_version");snap.countDown();try{if(!release.await(5,TimeUnit.SECONDS))throw new IllegalStateException("release timeout");}catch(InterruptedException e){throw new RuntimeException(e);}
            return new LinkedHashMap<>(Map.of("kind","fact_card","data_version",version,"cache_hit",false,"rows",mapper.query(sid,query().normalize()).stream().map(Rows::json).toList()));
        })));
        assertTrue(snap.await(5,TimeUnit.SECONDS));
        try{
            new TransactionTemplate(transactions).executeWithoutResult(status->{db.update("UPDATE order_items SET quantity=2");stores.bump(sid);});
            assertEquals(1L,Rows.number(service.query(user,sid,query()),"data_version"));
        }finally{release.countDown();}
        assertEquals(0L,Rows.number(old.get(5,TimeUnit.SECONDS),"data_version"));
        Map<String,Object> current=service.query(user,sid,query());assertEquals(1L,Rows.number(current,"data_version"));
        assertEquals("20.00",((List<Map<String,Object>>)current.get("rows")).getFirst().get("value"));
    }
}
