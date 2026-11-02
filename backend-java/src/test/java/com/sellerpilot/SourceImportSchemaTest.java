package com.sellerpilot;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class SourceImportSchemaTest {
    @Autowired JdbcTemplate db;

    @Test void migrationCreatesProvenanceTablesAndRequiredColumns(){
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='source_import_batches'",Integer.class));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='source_import_rows'",Integer.class));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='source_import_rows' AND column_name='raw_fields' AND data_type='json'",Integer.class));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM information_schema.table_constraints WHERE constraint_schema=DATABASE() AND table_name='source_import_rows' AND constraint_type='FOREIGN KEY'",Integer.class));
    }
}
