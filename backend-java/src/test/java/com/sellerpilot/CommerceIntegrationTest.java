package com.sellerpilot;

import static org.junit.jupiter.api.Assertions.*;
import java.net.URI;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

@EnabledIfEnvironmentVariable(named="JAVA_IT_MYSQL_URL",matches=".+")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class CommerceIntegrationTest {
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry){
        String url=System.getenv("JAVA_IT_MYSQL_URL");
        if(url==null||!url.matches("jdbc:mysql://[^/]+/sellerpilot_java_test(?:\\?.*)?"))throw new IllegalArgumentException("只允许独立 Java 测试库");
        registry.add("spring.datasource.url",()->url);
        registry.add("sellerpilot.cache.enabled",()->false);
    }
    @Value("${local.server.port}")int port;
    @Autowired JdbcTemplate db;
    @Autowired JsonMapper json;
    final HttpClient http=HttpClient.newHttpClient();
    String token;long sid;
    record Reply(int status,Map<String,Object> body){}
    Reply send(String method,String path,Object body,String token)throws Exception{
        HttpRequest.Builder builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).header("Content-Type","application/json");
        if(token!=null)builder.header("Authorization","Bearer "+token);
        builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        HttpResponse<String> response=http.send(builder.build(),HttpResponse.BodyHandlers.ofString());
        return new Reply(response.statusCode(),json.readValue(response.body(),Map.class));
    }
    @BeforeEach void fixtures()throws Exception{
        for(String table:List.of("audit_logs","action_executions","action_proposals","import_batches","order_items","orders","products","store_memberships","stores","users"))db.update("DELETE FROM "+table);
        assertEquals(201,send("POST","/auth/register",Map.of("username","owner","password","Password123!"),null).status);
        token=(String)send("POST","/auth/login",Map.of("username","owner","password","Password123!"),null).body.get("access_token");
        Reply store=send("POST","/stores",Map.of("name","Fixture","platform","amazon"),token);assertEquals(201,store.status,store.body.toString());
        sid=((Number)store.body.get("id")).longValue();
        db.update("INSERT INTO products(store_id,sku,title,price,currency,stock) VALUES(?,?,?,?,?,?)",sid,"JAR-001","Storage Jar","10.00","USD",8);
        long pid=db.queryForObject("SELECT id FROM products WHERE store_id=?",Long.class,sid);
        db.update("INSERT INTO orders(store_id,external_order_id,ordered_at,status,currency) VALUES(?,?,'2026-10-02 00:00:00','paid','USD')",sid,"O1");
        long oid=db.queryForObject("SELECT id FROM orders WHERE store_id=?",Long.class,sid);
        for(int line:List.of(1,2))db.update("INSERT INTO order_items(store_id,order_id,product_id,line_no,quantity,unit_price,refund_amount) VALUES(?,?,?,?,2,10,1)",sid,oid,pid,line);
    }
    Map<String,Object> query(String metric){return Map.of("metric",metric,"group_by","currency","start","2026-10-01T00:00:00Z","end","2026-10-03T00:00:00Z");}
    @Test void queryMatchesHandCalculationAndDoesNotCountJoinDuplicates()throws Exception{
        Reply sales=send("POST","/api/v2/stores/"+sid+"/metrics/query",query("sales"),token);
        assertEquals(200,sales.status,sales.body.toString());
        List<Map<String,Object>> rows=(List<Map<String,Object>>)sales.body.get("rows");
        assertEquals("38.00",rows.getFirst().get("value"));assertEquals("40.00",rows.getFirst().get("gross_sales"));
        Reply orders=send("POST","/api/v2/stores/"+sid+"/metrics/query",query("orders"),token);
        assertEquals(1,((Number)((List<Map<String,Object>>)orders.body.get("rows")).getFirst().get("value")).intValue());
        assertEquals("REPEATABLE-READ",db.queryForObject("SELECT @@transaction_isolation",String.class));
    }
    @Test void crossStoreAccessIsRejected()throws Exception{
        send("POST","/auth/register",Map.of("username","other","password","Password123!"),null);
        String other=(String)send("POST","/auth/login",Map.of("username","other","password","Password123!"),null).body.get("access_token");
        assertEquals(404,send("POST","/api/v2/stores/"+sid+"/metrics/query",query("sales"),other).status);
        assertEquals(401,send("POST","/api/v2/stores/"+sid+"/metrics/query",query("sales"),null).status);
    }
    @Test void currencyBucketsAndExclusiveUpperBoundaryRemainIndependent()throws Exception{
        db.update("INSERT INTO orders(store_id,external_order_id,ordered_at,status,currency) VALUES(?,'EUR-IN','2026-10-02','paid','EUR'),(?,'USD-END','2026-10-03','paid','USD')",sid,sid);
        Reply result=send("POST","/api/v2/stores/"+sid+"/metrics/query",query("orders"),token);
        assertEquals(200,result.status);
        List<Map<String,Object>> rows=(List<Map<String,Object>>)result.body.get("rows");
        assertEquals(2,rows.size());
        assertEquals(Set.of("EUR","USD"),new HashSet<>(rows.stream().map(r->(String)r.get("currency")).toList()));
        assertTrue(rows.stream().allMatch(r->((Number)r.get("value")).intValue()==1));
    }
    @Test void permissionRevocationTakesEffectWithTheSameJwt()throws Exception{
        send("POST","/auth/register",Map.of("username","viewer","password","Password123!"),null);
        String viewer=(String)send("POST","/auth/login",Map.of("username","viewer","password","Password123!"),null).body.get("access_token");
        assertEquals(200,send("PUT","/api/v2/stores/"+sid+"/members",Map.of("username","viewer","role","viewer"),token).status);
        assertEquals(200,send("POST","/api/v2/stores/"+sid+"/metrics/query",query("sales"),viewer).status);
        assertEquals(200,send("PUT","/api/v2/stores/"+sid+"/members",Map.of("username","viewer","role","viewer","active",false),token).status);
        assertEquals(404,send("POST","/api/v2/stores/"+sid+"/metrics/query",query("sales"),viewer).status);
    }
    Reply csv(String kind,String data)throws Exception{
        String boundary="SellerPilotBoundary";String body="--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\"orders.csv\"\r\nContent-Type: text/csv\r\n\r\n"+data+"\r\n--"+boundary+"--\r\n";
        HttpRequest request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/stores/"+sid+"/imports/"+kind))
            .header("Authorization","Bearer "+token).header("Content-Type","multipart/form-data; boundary="+boundary)
            .POST(HttpRequest.BodyPublishers.ofString(body,StandardCharsets.UTF_8)).build();
        HttpResponse<String> result=http.send(request,HttpResponse.BodyHandlers.ofString());return new Reply(result.statusCode(),json.readValue(result.body(),Map.class));
    }
    @Test void csvDeduplicatesFilesAndUpdatesBusinessKeysAtomically()throws Exception{
        String header="external_order_id,ordered_at,status,currency\n";
        String file=header+"O2,2026-10-02T08:00:00+08:00,paid,USD\n";
        Reply first=csv("orders",file);assertEquals(200,first.status,first.body.toString());
        assertEquals(false,first.body.get("duplicate"));assertEquals(true,csv("orders",file).body.get("duplicate"));
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM orders",Integer.class));
        Reply changed=csv("orders",header+"O2,2026-10-02T08:00:00+08:00,shipped,USD\n");assertEquals(200,changed.status);
        assertEquals("shipped",db.queryForObject("SELECT status FROM orders WHERE external_order_id='O2'",String.class));
        long version=db.queryForObject("SELECT data_version FROM stores WHERE id=?",Long.class,sid);
        Reply invalid=csv("orders",header+"O2,2026-10-02T08:00:00+08:00,paid,EUR\nO3,2026-10-02T00:00:00Z,paid,USD\n");
        assertEquals(422,invalid.status);assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM orders WHERE external_order_id='O3'",Integer.class));
        assertEquals(version,db.queryForObject("SELECT data_version FROM stores WHERE id=?",Long.class,sid));
        String items="external_order_id,line_no,sku,quantity,unit_price,refund_amount\nO2,1,JAR-001,3,10.00,0.00\n";
        assertEquals(200,csv("order-items",items).status);assertEquals(true,csv("order-items",items).body.get("duplicate"));
        Reply metrics=send("POST","/api/v2/stores/"+sid+"/metrics/query",query("sales"),token);
        assertEquals("68.00",((List<Map<String,Object>>)metrics.body.get("rows")).getFirst().get("value"));
    }
    Map<String,Object> proposal(String title)throws Exception{
        long pid=db.queryForObject("SELECT id FROM products WHERE store_id=?",Long.class,sid);
        long version=db.queryForObject("SELECT version FROM products WHERE id=?",Long.class,pid);
        Reply result=send("POST","/api/v2/stores/"+sid+"/proposals",Map.of("product_id",pid,"product_version",version,"title",title),token);
        assertEquals(201,result.status,result.body.toString());return result.body;
    }
    @Test void databaseFailureRollsBackTheEntireImport()throws Exception{
        db.execute("ALTER TABLE orders ADD CONSTRAINT java_test_fail_import CHECK(external_order_id<>'FAIL-DB')");
        try{
            StringBuilder data=new StringBuilder("external_order_id,ordered_at,status,currency\n");
            for(int i=0;i<500;i++)data.append("OK-DB-").append(i).append(",2026-10-02T00:00:00Z,paid,USD\n");
            data.append("FAIL-DB,2026-10-02T00:00:00Z,paid,USD\n");
            assertEquals(500,csv("orders",data.toString()).status);
            assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM orders",Integer.class));
            assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM import_batches",Integer.class));
            assertEquals(0,db.queryForObject("SELECT data_version FROM stores WHERE id=?",Integer.class,sid));
        }finally{db.execute("ALTER TABLE orders DROP CHECK java_test_fail_import");}
    }
    Reply confirm(Map<String,Object> proposal,String key)throws Exception{
        return send("POST","/api/v2/stores/"+sid+"/proposals/"+proposal.get("id")+"/confirm",
            Map.of("confirmed",true,"payload_hash",proposal.get("payload_hash"),"idempotency_key",key),token);
    }
    @Test void oneHundredConcurrentConfirmationsProduceOneEffectiveWrite()throws Exception{
        Map<String,Object> p=proposal("Approved Storage Jar");CountDownLatch ready=new CountDownLatch(100),go=new CountDownLatch(1);
        List<Future<Reply>> futures=new ArrayList<>();List<Reply> replies=new ArrayList<>();long started=System.nanoTime();
        try(ExecutorService pool=Executors.newFixedThreadPool(100)){
            for(int i=0;i<100;i++)futures.add(pool.submit(()->{ready.countDown();go.await();return confirm(p,"one-confirm");}));
            assertTrue(ready.await(5,TimeUnit.SECONDS));go.countDown();
            for(Future<Reply> future:futures)replies.add(future.get(20,TimeUnit.SECONDS));
        }
        assertTrue(replies.stream().allMatch(r->r.status==200),replies.toString());
        assertTrue(replies.stream().allMatch(r->r.body.equals(replies.getFirst().body)));
        int executions=db.queryForObject("SELECT COUNT(*) FROM action_executions",Integer.class),audits=db.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE action='update_product_title'",Integer.class);
        assertEquals(1,executions);assertEquals(1,audits);assertEquals(2,db.queryForObject("SELECT version FROM products WHERE store_id=?",Integer.class,sid));
        Path output=Path.of("../docs/verification/java/approval-contention.json");Files.createDirectories(output.getParent());
        Files.writeString(output,json.writeValueAsString(Map.of("experiment","100-client approval contention, not throughput benchmark","requests",100,
            "elapsed_seconds",(System.nanoTime()-started)/1e9,"executions",executions,"audit_rows",audits,"response_statuses",replies.stream().map(Reply::status).toList(),"receipt",replies.getFirst().body)));
    }
    @Test void abaAndReusedIdempotencyKeyAreRejected()throws Exception{
        Map<String,Object> old=proposal("First proposed title");
        db.update("UPDATE products SET title='Intermediate',version=version+1 WHERE store_id=?",sid);
        db.update("UPDATE products SET title='Storage Jar',version=version+1 WHERE store_id=?",sid);
        assertEquals(409,confirm(old,"old").status);
        Map<String,Object> next=proposal("Current proposed title");Reply success=confirm(next,"new");assertEquals(200,success.status);
        assertEquals(success.body,confirm(next,"new").body);
        Map<String,Object> third=proposal("Third proposed title");assertEquals(409,confirm(third,"new").status);
        assertEquals(410,send("POST","/stores/"+sid+"/title-drafts/1/confirm",Map.of("confirmed",true),token).status);
    }
    @Test void failureInAuditRollsBackProductReceiptAndVersion()throws Exception{
        Map<String,Object> p=proposal("Must not persist");
        db.execute("ALTER TABLE audit_logs ADD CONSTRAINT java_test_fail_audit CHECK(action<>'update_product_title')");
        try{
            assertEquals(500,confirm(p,"injected-failure").status);
            assertEquals("Storage Jar",db.queryForObject("SELECT title FROM products WHERE store_id=?",String.class,sid));
            assertEquals(1,db.queryForObject("SELECT version FROM products WHERE store_id=?",Integer.class,sid));
            assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM action_executions",Integer.class));
            assertEquals(0,db.queryForObject("SELECT data_version FROM stores WHERE id=?",Integer.class,sid));
        }finally{db.execute("ALTER TABLE audit_logs DROP CHECK java_test_fail_audit");}
        assertEquals(200,confirm(p,"injected-failure").status);
    }
}
