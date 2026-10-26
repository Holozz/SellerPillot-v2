package com.sellerpilot.experiments;

import com.sellerpilot.analytics.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import org.apache.ibatis.mapping.*;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.scripting.defaults.DefaultParameterHandler;
import tools.jackson.databind.json.JsonMapper;

/** CLI evidence tool: uses the exact mapper/provider and parameter binding of the API. */
public class QueryPlanCli {
    public static void main(String[] args)throws Exception{
        if(args.length!=4)throw new IllegalArgumentException("usage: store-id query.json stage output.json");
        String url=System.getenv().getOrDefault("JAVA_MYSQL_URL","jdbc:mysql://127.0.0.1:3310/sellerpilot_java?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true");
        if(!url.matches("jdbc:mysql://[^/]+/sellerpilot_java(?:\\?.*)?"))throw new IllegalArgumentException("只能对独立Java实验库运行");
        JsonMapper json=JsonMapper.builder().findAndAddModules().build();QuerySpec query=json.readValue(Path.of(args[1]).toFile(),QuerySpec.class);
        Configuration config=new Configuration();config.addMapper(MetricsMapper.class);
        MappedStatement statement=config.getMappedStatement(MetricsMapper.class.getName()+".query");
        Map<String,Object> parameters=Map.of("sid",Long.parseLong(args[0]),"query",query.normalize());BoundSql bound=statement.getBoundSql(parameters);
        Map<String,Object> report=new LinkedHashMap<>();
        try(Connection connection=DriverManager.getConnection(url,System.getenv("JAVA_DB_USER"),System.getenv("JAVA_DB_PASSWORD"))){
            List<String> plan=new ArrayList<>();
            try(PreparedStatement explain=connection.prepareStatement("EXPLAIN ANALYZE "+bound.getSql())){
                explain.setQueryTimeout(30);
                new DefaultParameterHandler(statement,parameters,bound).setParameters(explain);
                try(ResultSet rows=explain.executeQuery()){while(rows.next())plan.add(rows.getString(1));}
            }
            List<Map<String,Object>> indexes=new ArrayList<>();
            try(Statement sql=connection.createStatement();ResultSet rows=sql.executeQuery("SHOW INDEX FROM orders")){
                while(rows.next())indexes.add(Map.of("name",rows.getString("Key_name"),"column",rows.getString("Column_name"),"seq",rows.getInt("Seq_in_index")));
            }
            report.put("stage",args[2]);report.put("sql_source",MetricsMapper.class.getName()+".query");report.put("sql",bound.getSql());
            report.put("query",query.normalize().asMap());report.put("store_id",Long.parseLong(args[0]));report.put("plan",plan);report.put("indexes",indexes);
            report.put("jdbc_driver",connection.getMetaData().getDriverVersion());report.put("mysql_version",connection.getMetaData().getDatabaseProductVersion());
            report.put("scope","single SQL execution plan; not an API p95 or throughput measurement");
        }
        Files.writeString(Path.of(args[3]),json.writerWithDefaultPrettyPrinter().writeValueAsString(report));
    }
}
