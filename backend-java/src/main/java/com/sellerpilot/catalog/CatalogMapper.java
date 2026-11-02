package com.sellerpilot.catalog;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.*;

@Mapper
public interface CatalogMapper {
    @Select("SELECT id,internal_sku,title,base_unit FROM catalog_products WHERE owner_id=#{owner} ORDER BY internal_sku")
    List<Map<String,Object>> products(long owner);
    @Select("SELECT * FROM catalog_products WHERE owner_id=#{owner} AND internal_sku=#{sku}")
    Map<String,Object> product(@Param("owner")long owner,@Param("sku")String sku);
    @Insert("INSERT INTO catalog_products(owner_id,internal_sku,title,base_unit) VALUES(#{owner},#{sku},#{title},#{unit})")
    void insertProduct(@Param("owner")long owner,@Param("sku")String sku,@Param("title")String title,@Param("unit")String unit);
    @Select("SELECT * FROM catalog_products WHERE owner_id=#{owner} AND id=#{id}")
    Map<String,Object> productById(@Param("owner")long owner,@Param("id")long id);
    @Select("<script>SELECT m.* FROM store_product_mapping_revisions m WHERE m.store_id=#{store} AND m.source_sku IN <foreach collection='skus' item='sku' open='(' separator=',' close=')'>#{sku}</foreach> AND m.mapping_version=(SELECT MAX(m2.mapping_version) FROM store_product_mapping_revisions m2 WHERE m2.store_id=m.store_id AND m2.source_sku=m.source_sku)</script>")
    List<Map<String,Object>> latestMappings(@Param("store")long store,@Param("skus")List<String> skus);
    @Select("SELECT COALESCE(MAX(mapping_version),0)+1 FROM store_product_mapping_revisions WHERE store_id=#{store} AND source_sku=#{sku}")
    long nextVersion(@Param("store")long store,@Param("sku")String sku);
    @Insert("INSERT INTO store_product_mapping_revisions(owner_id,store_id,source_sku,mapping_version,catalog_product_id,created_by) VALUES(#{owner},#{store},#{sourceSku},#{version},#{product},#{user})")
    void map(@Param("owner")long owner,@Param("store")long store,@Param("sourceSku")String sourceSku,@Param("version")long version,@Param("product")long product,@Param("user")long user);
    @Select("SELECT m.source_sku,m.mapping_version,p.id AS catalog_product_id,p.internal_sku,p.title,p.base_unit FROM store_product_mapping_revisions m JOIN catalog_products p ON p.owner_id=m.owner_id AND p.id=m.catalog_product_id WHERE m.store_id=#{store} AND m.mapping_version=(SELECT MAX(m2.mapping_version) FROM store_product_mapping_revisions m2 WHERE m2.store_id=m.store_id AND m2.source_sku=m.source_sku) ORDER BY m.source_sku")
    List<Map<String,Object>> mappings(long store);
}
