package com.sellerpilot.analytics;

import java.util.Map;

public class MetricsSql {
    public String query(Map<String,Object> parameters){
        QuerySpec.Normalized q=(QuerySpec.Normalized)parameters.get("query");
        if(q.metric().equals("stock"))return "SELECT id AS product_id,sku,title,currency,stock FROM products WHERE store_id=#{sid}"
            +(q.threshold()!=null?" AND stock < #{query.threshold}":"")+" ORDER BY stock,id LIMIT #{query.limit}";
        String value=switch(q.metric()){
            case "sales" -> "COALESCE(SUM(i.quantity*i.unit_price-i.refund_amount),0)";
            case "quantity" -> "COALESCE(SUM(i.quantity),0)";
            case "orders" -> "COUNT(DISTINCT o.id)";
            default -> throw new IllegalArgumentException("Invalid normalized metric");
        };
        boolean product=q.groupBy().equals("product");
        return "<script>SELECT o.currency,"+(product?"p.id AS product_id,p.sku,p.title,":"")+value+" AS value,"
            +"COALESCE(SUM(i.quantity*i.unit_price),0) AS gross_sales,COALESCE(SUM(i.refund_amount),0) AS refund_amount,"
            +"COALESCE(SUM(i.quantity*i.unit_price-i.refund_amount),0) AS net_sales "
            +"FROM orders o LEFT JOIN order_items i ON i.order_id=o.id AND i.store_id=o.store_id "
            +(product?"JOIN products p ON p.id=i.product_id AND p.store_id=o.store_id ":"")
            +"WHERE o.store_id=#{sid} AND o.ordered_at &gt;= #{query.start} AND o.ordered_at &lt; #{query.end} "
            +"AND o.status IN <foreach collection='query.statuses' item='state' open='(' separator=',' close=')'>#{state}</foreach> "
            +"GROUP BY o.currency"+(product?",p.id,p.sku,p.title":"")
            +" ORDER BY o.currency,value DESC"+(product?",p.id":"")+" LIMIT #{query.limit}</script>";
    }
}
