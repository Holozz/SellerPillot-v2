package com.sellerpilot;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class SourceImportSchemaTest {
    @Autowired JdbcTemplate db;

    @Test void migrationsCreateImportAndBatchCostTables(){
        for(String table:new String[]{"source_import_batches","source_import_rows","catalog_products",
                "store_product_mapping_revisions","procurement_batch_revisions","procurement_batch_allocations"}){
            assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name=?",Integer.class,table),table);
        }
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='source_import_rows' AND column_name='raw_fields' AND data_type='json'",Integer.class));
        assertTrue(db.queryForObject("SELECT COUNT(*) FROM information_schema.table_constraints WHERE constraint_schema=DATABASE() AND table_name='procurement_batch_revisions' AND constraint_type='FOREIGN KEY'",Integer.class)>=1);
    }
}
