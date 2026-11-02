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
    record Reply(int status,Map<String,Object> body,List<?> rows){}
    Reply send(String method,String path,Object body,String token)throws Exception{
        HttpRequest.Builder builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).header("Content-Type","application/json");
        if(token!=null)builder.header("Authorization","Bearer "+token);
        builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        HttpResponse<String> response=http.send(builder.build(),HttpResponse.BodyHandlers.ofString());
        if(response.body().startsWith("["))return new Reply(response.statusCode(),Map.of(),json.readValue(response.body(),List.class));
        return new Reply(response.statusCode(),json.readValue(response.body(),Map.class),List.of());
    }
    @BeforeEach void fixtures()throws Exception{
        for(String table:List.of("margin_reports","sales_refund_revisions","sales_line_revisions","sales_order_revisions","procurement_batch_allocations","procurement_batch_revisions","store_product_mapping_revisions","catalog_products","source_import_rows","source_import_batches","fx_rates","fx_rate_sets","audit_logs","action_executions","action_proposals","import_batches","order_items","orders","products","store_memberships","stores","users"))db.update("DELETE FROM "+table);
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
    @Test void exchangeRateSetsPreserveVersionsAndRejectDuplicateDates()throws Exception{
        Map<String,Object> rate=Map.of("base_currency","USD","effective_date","2026-11-01","rate","7.00");
        Map<String,Object> body=Map.of("source","manual-provider","source_revision","v1","rates",List.of(rate));
        Reply created=send("POST","/api/v2/stores/"+sid+"/fx-rate-sets",body,token);assertEquals(200,created.status,created.body.toString());
        assertEquals(200,send("GET","/api/v2/stores/"+sid+"/fx-rate-sets/"+created.body.get("id"),null,token).status);
        assertEquals(409,send("POST","/api/v2/stores/"+sid+"/fx-rate-sets",body,token).status);
        assertEquals(422,send("POST","/api/v2/stores/"+sid+"/fx-rate-sets",Map.of("source","manual-provider","source_revision","v2","rates",List.of(rate,rate)),token).status);
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM fx_rate_sets",Integer.class));
    }
    @Test void queryMatchesHandCalculationAndDoesNotCountJoinDuplicates()throws Exception{
        Reply sales=send("POST","/api/v2/stores/"+sid+"/metrics/query",query("sales"),token);
        assertEquals(200,sales.status,sales.body.toString());
        List<Map<String,Object>> rows=(List<Map<String,Object>>)sales.body.get("rows");
        assertEquals("38.00",rows.getFirst().get("value"));assertEquals("40.00",rows.getFirst().get("gross_sales"));
        Reply orders=send("POST","/api/v2/stores/"+sid+"/metrics/query",query("orders"),token);
        assertEquals(1,((Number)((List<Map<String,Object>>)orders.body.get("rows")).getFirst().get("value")).intValue());
        assertEquals("REPEATABLE-READ",db.queryForObject("SELECT @@transaction_isolation",String.class));
    }
    @Test void catalogSkuMappingsAreOwnerScopedAndVersioned()throws Exception{
        Reply created=send("POST","/api/v2/stores/"+sid+"/catalog-products",Map.of("internal_sku","MUG-01","title","Travel Mug","base_unit","piece"),token);
        assertEquals(201,created.status,created.body.toString());
        Reply first=send("PUT","/api/v2/stores/"+sid+"/product-mappings/MUG-US",Map.of("internal_sku","MUG-01"),token);
        assertEquals(200,first.status,first.body.toString());assertEquals(1,((Number)first.body.get("mapping_version")).intValue());
        Reply revised=send("PUT","/api/v2/stores/"+sid+"/product-mappings/MUG-US",Map.of("internal_sku","MUG-01"),token);
        assertEquals(2,((Number)revised.body.get("mapping_version")).intValue());
        Reply secondStore=send("POST","/stores",Map.of("name","Second","platform","shopify"),token);assertEquals(201,secondStore.status);
        long sid2=((Number)secondStore.body.get("id")).longValue();
        Reply crossStore=send("PUT","/api/v2/stores/"+sid2+"/product-mappings/MUG-EU",Map.of("internal_sku","MUG-01"),token);
        assertEquals(200,crossStore.status,crossStore.body.toString());
        assertEquals(1,((Number)crossStore.body.get("mapping_version")).intValue());
        assertEquals(1,send("GET","/api/v2/stores/"+sid2+"/product-mappings",null,token).rows.size());
        send("POST","/auth/register",Map.of("username","catalog-other","password","Password123!"),null);
        String other=(String)send("POST","/auth/login",Map.of("username","catalog-other","password","Password123!"),null).body.get("access_token");
        Reply otherStore=send("POST","/stores",Map.of("name","Other owner","platform","amazon"),other);assertEquals(201,otherStore.status);
        assertEquals(404,send("PUT","/api/v2/stores/"+otherStore.body.get("id")+"/product-mappings/MUG-OTHER",Map.of("internal_sku","MUG-01"),other).status);
    }
    @Test void crossStoreAccessIsRejected()throws Exception{
        send("POST","/auth/register",Map.of("username","other","password","Password123!"),null);
        String other=(String)send("POST","/auth/login",Map.of("username","other","password","Password123!"),null).body.get("access_token");
        assertEquals(404,send("POST","/api/v2/stores/"+sid+"/metrics/query",query("sales"),other).status);
        assertEquals(404,send("GET","/api/v2/stores/"+sid+"/source-imports",null,other).status);
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
        HttpResponse<String> result=http.send(request,HttpResponse.BodyHandlers.ofString());return new Reply(result.statusCode(),json.readValue(result.body(),Map.class),List.of());
    }
    Reply sourceCsv(long storeId,String kind,String data)throws Exception{return sourceCsv(storeId,kind,data,false);}
    Reply sourceCsv(long storeId,String kind,String data,boolean confirmConflicts)throws Exception{
        String boundary="SellerPilotSourceBoundary";String body="--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\"source.csv\"\r\nContent-Type: text/csv\r\n\r\n"+data+"\r\n--"+boundary+"--\r\n";
        String url="http://127.0.0.1:"+port+"/api/v2/stores/"+storeId+"/source-imports/"+kind+(confirmConflicts?"?confirm_conflicts=true":"");
        HttpRequest request=HttpRequest.newBuilder(URI.create(url))
            .header("Authorization","Bearer "+token).header("Content-Type","multipart/form-data; boundary="+boundary)
            .POST(HttpRequest.BodyPublishers.ofString(body,StandardCharsets.UTF_8)).build();
        HttpResponse<String> result=http.send(request,HttpResponse.BodyHandlers.ofString());return new Reply(result.statusCode(),json.readValue(result.body(),Map.class),List.of());
    }
    @Test void sourceImportPreservesOriginalFileAndDeduplicatesThePublishedBatch()throws Exception{
        Reply store=send("POST","/stores",Map.of("name","Market A","platform","market-a","source_store_key","store-a"),token);assertEquals(201,store.status);
        long sourceSid=((Number)store.body.get("id")).longValue();
        String file="platform_code,store_key,external_order_id,ordered_at,paid_at,status,currency\nmarket-a,store-a,A-100,2026-11-01T08:00:00+00:00,2026-11-01T08:05:00+00:00,paid,USD\n";
        Reply first=sourceCsv(sourceSid,"orders",file);assertEquals(200,first.status,first.body.toString());assertEquals("PUBLISHED",first.body.get("status"));
        assertEquals(1,first.body.get("row_count"));assertEquals(false,first.body.get("duplicate"));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM source_import_rows WHERE store_id=?",Integer.class,sourceSid));
        assertEquals(file.trim(),new String(db.queryForObject("SELECT source_file FROM source_import_batches WHERE store_id=?",byte[].class,sourceSid),StandardCharsets.UTF_8).trim());
        Reply repeated=sourceCsv(sourceSid,"orders",file);assertEquals(true,repeated.body.get("duplicate"));assertEquals(first.body.get("import_id"),repeated.body.get("import_id"));
        String wrongStore=file.replace("store-a","store-b");assertEquals(422,sourceCsv(sourceSid,"orders",wrongStore).status);
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM source_import_batches WHERE store_id=?",Integer.class,sourceSid));
        Reply catalogProduct=send("POST","/api/v2/stores/"+sourceSid+"/catalog-products",Map.of("internal_sku","MUG-01","title","Travel Mug","base_unit","piece"),token);assertEquals(201,catalogProduct.status);
        assertEquals(200,send("PUT","/api/v2/stores/"+sourceSid+"/product-mappings/MUG-US",Map.of("internal_sku","MUG-01"),token).status);
        String lot="platform_code,store_key,source_batch_id,external_sku,received_at,quantity,unit_purchase_cost,currency\nmarket-a,store-a,A-BATCH-01,MUG-US,2026-10-31T08:00:00+00:00,10,4.00,USD\n";
        assertEquals(200,sourceCsv(sourceSid,"procurement-batches",lot).status);
        String line="platform_code,store_key,external_line_id,external_order_id,external_sku,quantity,unit_price,discount_amount,currency,source_batch_id\nmarket-a,store-a,A-100-1,A-100,MUG-US,2,10.00,2.00,USD,A-BATCH-01\n";
        assertEquals(200,sourceCsv(sourceSid,"order-items",line).status);
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM sales_order_revisions WHERE store_id=?",Integer.class,sourceSid));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM sales_line_revisions WHERE store_id=?",Integer.class,sourceSid));
        assertEquals("2.000000",db.queryForObject("SELECT quantity FROM procurement_batch_allocations WHERE store_id=?",String.class,sourceSid));
        String refund="platform_code,store_key,external_refund_id,external_line_id,confirmed_at,refund_amount,currency,returned_quantity,restocked\nmarket-a,store-a,A-REF-1,A-100-1,2026-11-03T08:00:00+00:00,3.00,USD,1,true\n";
        assertEquals(200,sourceCsv(sourceSid,"refunds",refund).status);
        String correctedRefund=refund.replace(",3.00,",",4.00,");
        assertEquals(409,sourceCsv(sourceSid,"refunds",correctedRefund).status);
        assertEquals(200,sourceCsv(sourceSid,"refunds",correctedRefund,true).status);
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM sales_refund_revisions WHERE store_id=?",Integer.class,sourceSid));
        Reply revisions=send("GET","/api/v2/stores/"+sourceSid+"/source-revisions/refunds?key=A-REF-1",null,token);assertEquals(200,revisions.status);
        List<Map<String,Object>> history=(List<Map<String,Object>>)revisions.body.get("rows");assertEquals(2,history.size());
        assertEquals("3.000000",history.getFirst().get("refund_amount"));assertEquals("4.000000",history.getLast().get("refund_amount"));assertNotNull(history.getFirst().get("batch_id"));
        Reply imports=send("GET","/api/v2/stores/"+sourceSid+"/source-imports?limit=1",null,token);assertEquals(200,imports.status);
        List<Map<String,Object>> batches=(List<Map<String,Object>>)imports.body.get("rows");assertEquals(1,batches.size());
        Object batchId=batches.getFirst().get("id");
        Reply sourceRows=send("GET","/api/v2/stores/"+sourceSid+"/source-imports/"+batchId+"/rows",null,token);assertEquals(200,sourceRows.status);
        assertInstanceOf(Map.class,((List<Map<String,Object>>)sourceRows.body.get("rows")).getFirst().get("normalized_fields"));
        HttpResponse<byte[]> download=http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v2/stores/"+sourceSid+"/source-imports/"+batchId+"/file")).header("Authorization","Bearer "+token).GET().build(),HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200,download.statusCode());assertArrayEquals(file.getBytes(StandardCharsets.UTF_8),download.body());
        assertEquals(422,send("GET","/api/v2/stores/"+sourceSid+"/source-imports?limit=101",null,token).status);
        String noRefunds="platform_code,store_key,external_refund_id,external_line_id,confirmed_at,refund_amount,currency,returned_quantity,restocked\n";
        Reply empty=sourceCsv(sourceSid,"refunds",noRefunds);assertEquals(200,empty.status,empty.body.toString());assertEquals(0,empty.body.get("row_count"));
        String invalidLine="platform_code,store_key,external_line_id,external_order_id,external_sku,quantity,unit_price,discount_amount,currency,source_batch_id\nmarket-a,store-a,A-100-1,A-100,MUG-US,2,10.00,25.00,USD,A-BATCH-01\n";
        assertEquals(422,sourceCsv(sourceSid,"order-items",invalidLine).status);
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM source_import_batches WHERE store_id=? AND kind='order-items'",Integer.class,sourceSid));
        Reply marketB=send("POST","/stores",Map.of("name","Market B","platform","market-b","source_store_key","store-b"),token);assertEquals(201,marketB.status);
        long marketBSid=((Number)marketB.body.get("id")).longValue();
        String marketBOrder="platform_code,store_key,external_order_id,order_timestamp,payment_timestamp,order_state,order_currency\nmarket-b,store-b,B-200,2026-11-02T09:00:00+00:00,2026-11-02T09:10:00+00:00,settled,EUR\n";
        assertEquals(200,sourceCsv(marketBSid,"orders",marketBOrder).status);
        assertEquals("paid",db.queryForObject("SELECT JSON_UNQUOTE(JSON_EXTRACT(normalized_fields,'$.status')) FROM source_import_rows WHERE store_id=?",String.class,marketBSid));
        assertEquals(200,send("PUT","/api/v2/stores/"+marketBSid+"/product-mappings/MUG-EU",Map.of("internal_sku","MUG-01"),token).status);
        String lotB="platform_code,store_key,purchase_lot,merchant_sku,receipt_timestamp,received_units,cost_per_unit,cost_currency\nmarket-b,store-b,B-BATCH-09,MUG-EU,2026-10-30T10:00:00+00:00,5,12.50,EUR\n";
        assertEquals(200,sourceCsv(marketBSid,"procurement-batches",lotB).status);
        String lineB="platform_code,store_key,line_key,order_number,merchant_sku,units,unit_sale_price,line_discount,price_currency,purchase_lot\nmarket-b,store-b,B-200-1,B-200,MUG-EU,1,20.00,0.00,EUR,B-BATCH-09\n";
        assertEquals(200,sourceCsv(marketBSid,"order-items",lineB).status);
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM sales_line_revisions",Integer.class));
        List<Map<String,Object>> rates=List.of(
            Map.of("base_currency","USD","effective_date","2026-10-31","rate","6.90"),
            Map.of("base_currency","USD","effective_date","2026-11-01","rate","7.00"),
            Map.of("base_currency","USD","effective_date","2026-11-03","rate","7.20"),
            Map.of("base_currency","EUR","effective_date","2026-10-30","rate","7.80"),
            Map.of("base_currency","EUR","effective_date","2026-11-02","rate","8.00"));
        Reply rateSet=send("POST","/api/v2/stores/"+sourceSid+"/fx-rate-sets",Map.of("source","fixture","source_revision","margin-v1","rates",rates),token);assertEquals(200,rateSet.status);
        Reply report=send("POST","/api/v2/margin-reports",Map.of("store_ids",List.of(sourceSid,marketBSid),"fx_rate_set_id",rateSet.body.get("id"),"start","2026-11-01","end","2026-11-03"),token);assertEquals(200,report.status,report.body.toString());
        Map<String,Object> result=(Map<String,Object>)report.body.get("result");assertEquals("133.30",result.get("gross_margin_cny"));assertEquals("286.00",result.get("sales_net_cny"));assertEquals("152.70",result.get("procurement_cost_cny"));assertEquals(true,result.get("complete"));
        Files.writeString(Path.of("../docs/verification/p0-contract/actual-margin.json"),json.writeValueAsString(Map.of("environment",db.queryForObject("SELECT VERSION()",String.class),"expected",Map.of("sales_net_cny","286.00","procurement_cost_cny","152.70","gross_margin_cny","133.30"),"actual",result)));
        assertEquals(result,send("POST","/api/v2/margin-reports/"+report.body.get("id")+"/recompute",null,token).body);
        Reply refundReport=send("POST","/api/v2/margin-reports",Map.of("store_ids",List.of(sourceSid),"fx_rate_set_id",rateSet.body.get("id"),"start","2026-11-03","end","2026-11-04"),token);assertEquals(200,refundReport.status,refundReport.body.toString());
        assertEquals("-1.20",((Map<String,Object>)refundReport.body.get("result")).get("gross_margin_cny"));
        Reply partialSet=send("POST","/api/v2/stores/"+sourceSid+"/fx-rate-sets",Map.of("source","fixture","source_revision","partial","rates",List.of(rates.get(1))),token);
        Reply partial=send("POST","/api/v2/margin-reports",Map.of("store_ids",List.of(sourceSid,marketBSid),"fx_rate_set_id",partialSet.body.get("id"),"start","2026-11-01","end","2026-11-03"),token);assertEquals(200,partial.status);
        assertEquals(false,((Map<String,Object>)partial.body.get("result")).get("complete"));assertNull(((Map<String,Object>)partial.body.get("result")).get("gross_margin_cny"));
        assertEquals(result,((Map<String,Object>)send("GET","/api/v2/margin-reports/"+report.body.get("id"),null,token).body.get("result")));
        assertEquals(200,sourceCsv(sourceSid,"orders",file.replace(",paid,USD",",cancelled,USD"),true).status);
        Reply correctedReport=send("POST","/api/v2/margin-reports",Map.of("store_ids",List.of(sourceSid,marketBSid),"fx_rate_set_id",rateSet.body.get("id"),"start","2026-11-01","end","2026-11-03"),token);
        assertEquals(200,correctedReport.status,correctedReport.body.toString());assertEquals("62.50",((Map<String,Object>)correctedReport.body.get("result")).get("gross_margin_cny"));
        assertEquals(result,send("POST","/api/v2/margin-reports/"+report.body.get("id")+"/recompute",null,token).body);
        send("POST","/auth/register",Map.of("username","margin-viewer","password","Password123!"),null);
        String viewer=(String)send("POST","/auth/login",Map.of("username","margin-viewer","password","Password123!"),null).body.get("access_token");
        for(long storeId:List.of(sourceSid,marketBSid))assertEquals(200,send("PUT","/api/v2/stores/"+storeId+"/members",Map.of("username","margin-viewer","role","viewer"),token).status);
        assertEquals(200,send("GET","/api/v2/margin-reports/"+report.body.get("id"),null,viewer).status);
        send("PUT","/api/v2/stores/"+marketBSid+"/members",Map.of("username","margin-viewer","role","viewer","active",false),token);
        assertEquals(404,send("GET","/api/v2/margin-reports/"+report.body.get("id"),null,viewer).status);
        assertEquals(404,send("POST","/api/v2/margin-reports/"+report.body.get("id")+"/recompute",null,viewer).status);
    }
    @Test void sourceCorrectionsRequireConfirmationAndFailedLineBatchRollsBackAtomically()throws Exception{
        Reply store=send("POST","/stores",Map.of("name","Revision store","platform","market-a","source_store_key","revision-store"),token);assertEquals(201,store.status);
        long sourceSid=((Number)store.body.get("id")).longValue();
        String orderHeader="platform_code,store_key,external_order_id,ordered_at,paid_at,status,currency\n";
        String order=orderHeader+"market-a,revision-store,R-1,2026-11-01T08:00:00+00:00,2026-11-01T08:05:00+00:00,paid,USD\n";
        assertEquals(200,sourceCsv(sourceSid,"orders",order).status);
        String corrected=order.replace(",paid,USD",",shipped,USD");
        assertEquals(409,sourceCsv(sourceSid,"orders",corrected).status);
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM sales_order_revisions WHERE store_id=?",Integer.class,sourceSid));
        assertEquals(200,sourceCsv(sourceSid,"orders",corrected,true).status);
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM sales_order_revisions WHERE store_id=?",Integer.class,sourceSid));
        assertEquals(201,send("POST","/api/v2/stores/"+sourceSid+"/catalog-products",Map.of("internal_sku","R-MUG","title","Revision Mug","base_unit","piece"),token).status);
        assertEquals(200,send("PUT","/api/v2/stores/"+sourceSid+"/product-mappings/R-SKU",Map.of("internal_sku","R-MUG"),token).status);
        String batch="platform_code,store_key,source_batch_id,external_sku,received_at,quantity,unit_purchase_cost,currency\nmarket-a,revision-store,R-BATCH,R-SKU,2026-10-31T08:00:00+00:00,2,4.00,USD\n";
        assertEquals(200,sourceCsv(sourceSid,"procurement-batches",batch).status);
        String lineHeader="platform_code,store_key,external_line_id,external_order_id,external_sku,quantity,unit_price,discount_amount,currency,source_batch_id\n";
        String tooMany=lineHeader+"market-a,revision-store,R-LINE,R-1,R-SKU,3,10.00,0.00,USD,R-BATCH\n";
        assertEquals(409,sourceCsv(sourceSid,"order-items",tooMany).status);
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM source_import_batches WHERE store_id=? AND kind='order-items'",Integer.class,sourceSid));
        String valid=tooMany.replace(",3,10.00,",",2,10.00,");assertEquals(200,sourceCsv(sourceSid,"order-items",valid).status);
        String revised=valid.replace(",2,10.00,",",1,11.00,");
        assertEquals(409,sourceCsv(sourceSid,"order-items",revised).status);
        assertEquals(200,sourceCsv(sourceSid,"order-items",revised,true).status);
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM sales_line_revisions WHERE store_id=?",Integer.class,sourceSid));
        assertEquals("1.000000",db.queryForObject("SELECT quantity FROM procurement_batch_allocations WHERE store_id=? ORDER BY id DESC LIMIT 1",String.class,sourceSid));
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
