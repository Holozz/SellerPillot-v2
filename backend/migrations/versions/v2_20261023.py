"""SellerPilot v2 schema: frozen MySQL DDL, application models may evolve independently."""
from alembic import op
import sqlalchemy as sa
revision = 'v2_20261023'
down_revision = 'd6c7744dd9bc'
branch_labels = None
depends_on = None


def upgrade():
    op.add_column('products', sa.Column('version', sa.Integer(), nullable=False, server_default='1'))
    op.add_column('store', sa.Column('timezone', sa.String(60), nullable=False, server_default='UTC'))
    op.add_column('product_title_drafts', sa.Column('product_version', sa.Integer(), nullable=False, server_default='1'))
    # A v1 pending draft has no historical version; force regeneration rather than guess.
    op.execute("UPDATE product_title_drafts SET product_version=0 WHERE status='pending'")
    for constraint in sa.inspect(op.get_bind()).get_unique_constraints('products'):
        if constraint['column_names'] == ['sku']:
            op.drop_constraint(constraint['name'], 'products', type_='unique')
    op.create_unique_constraint('uq_products_store_sku','products',['store_id','sku'])
    op.create_index('ix_orders_store_status_time','orders',['store_id','status','ordered_at'])
    op.execute('\nCREATE TABLE audit_logs (\n\tid VARCHAR(32) NOT NULL, \n\tstore_id INTEGER NOT NULL, \n\tuser_id INTEGER NOT NULL, \n\taction VARCHAR(40) NOT NULL, \n\ttarget VARCHAR(100) NOT NULL, \n\tdetail JSON NOT NULL, \n\tcreated_at DATETIME NOT NULL, \n\tPRIMARY KEY (id), \n\tFOREIGN KEY(store_id) REFERENCES store (id), \n\tFOREIGN KEY(user_id) REFERENCES users (id)\n)\n\n')
    op.execute('CREATE INDEX ix_audit_logs_store_id ON audit_logs (store_id)')
    op.execute('\nCREATE TABLE jobs (\n\tid VARCHAR(32) NOT NULL, \n\tstore_id INTEGER NOT NULL, \n\tuser_id INTEGER NOT NULL, \n\tkind VARCHAR(24) NOT NULL, \n\tidempotency_key VARCHAR(100) NOT NULL, \n\tpayload JSON NOT NULL, \n\ttrace_context JSON NOT NULL, \n\tstatus VARCHAR(20) NOT NULL, \n\ttoken INTEGER NOT NULL, \n\tattempt INTEGER NOT NULL, \n\tlease_until DATETIME, \n\tavailable_at DATETIME NOT NULL, \n\tcancel_requested BOOL NOT NULL, \n\tresult JSON, \n\terror TEXT, \n\tcreated_at DATETIME NOT NULL, \n\tupdated_at DATETIME NOT NULL, \n\tPRIMARY KEY (id), \n\tUNIQUE (store_id, kind, idempotency_key), \n\tFOREIGN KEY(store_id) REFERENCES store (id), \n\tFOREIGN KEY(user_id) REFERENCES users (id)\n)\n\n')
    op.execute('CREATE INDEX ix_jobs_store_id ON jobs (store_id)')
    op.execute('CREATE INDEX ix_jobs_recovery ON jobs (status, lease_until)')
    op.execute('CREATE INDEX ix_jobs_status ON jobs (status)')
    op.execute('\nCREATE TABLE knowledge_bases (\n\tid VARCHAR(32) NOT NULL, \n\tstore_id INTEGER NOT NULL, \n\tname VARCHAR(160) NOT NULL, \n\trevision INTEGER NOT NULL, \n\tvisibility_version INTEGER NOT NULL, \n\tactive_build_id VARCHAR(32), \n\tPRIMARY KEY (id), \n\tFOREIGN KEY(store_id) REFERENCES store (id)\n)\n\n')
    op.execute('CREATE INDEX ix_knowledge_bases_store_id ON knowledge_bases (store_id)')
    op.execute('\nCREATE TABLE store_memberships (\n\tid INTEGER NOT NULL AUTO_INCREMENT, \n\tstore_id INTEGER NOT NULL, \n\tuser_id INTEGER NOT NULL, \n\t`role` VARCHAR(20) NOT NULL, \n\tactive BOOL NOT NULL, \n\tversion INTEGER NOT NULL, \n\tPRIMARY KEY (id), \n\tUNIQUE (store_id, user_id), \n\tFOREIGN KEY(store_id) REFERENCES store (id), \n\tFOREIGN KEY(user_id) REFERENCES users (id)\n)\n\n')
    op.execute('\nCREATE TABLE action_proposals (\n\tid VARCHAR(32) NOT NULL, \n\tstore_id INTEGER NOT NULL, \n\tproduct_id INTEGER NOT NULL, \n\tuser_id INTEGER NOT NULL, \n\tproduct_version INTEGER NOT NULL, \n\toriginal_title VARCHAR(200) NOT NULL, \n\tproposed_title VARCHAR(200) NOT NULL, \n\tpayload_hash VARCHAR(64) NOT NULL, \n\tevidence JSON NOT NULL, \n\tstatus VARCHAR(20) NOT NULL, \n\texpires_at DATETIME NOT NULL, \n\tcreated_at DATETIME NOT NULL, \n\tPRIMARY KEY (id), \n\tFOREIGN KEY(store_id) REFERENCES store (id), \n\tFOREIGN KEY(product_id) REFERENCES products (id), \n\tFOREIGN KEY(user_id) REFERENCES users (id)\n)\n\n')
    op.execute('CREATE INDEX ix_action_proposals_store_id ON action_proposals (store_id)')
    op.execute('\nCREATE TABLE documents (\n\tid VARCHAR(32) NOT NULL, \n\tkb_id VARCHAR(32) NOT NULL, \n\tstore_id INTEGER NOT NULL, \n\tname VARCHAR(200) NOT NULL, \n\tactive BOOL NOT NULL, \n\tcurrent_version_id VARCHAR(32), \n\tcreated_at DATETIME NOT NULL, \n\tPRIMARY KEY (id), \n\tFOREIGN KEY(kb_id) REFERENCES knowledge_bases (id), \n\tFOREIGN KEY(store_id) REFERENCES store (id)\n)\n\n')
    op.execute('CREATE INDEX ix_documents_kb_id ON documents (kb_id)')
    op.execute('\nCREATE TABLE index_builds (\n\tid VARCHAR(32) NOT NULL, \n\tkb_id VARCHAR(32) NOT NULL, \n\tjob_id VARCHAR(32) NOT NULL, \n\ttoken INTEGER NOT NULL, \n\trevision INTEGER NOT NULL, \n\tstatus VARCHAR(20) NOT NULL, \n\tmanifest JSON NOT NULL, \n\tcreated_at DATETIME NOT NULL, \n\tPRIMARY KEY (id), \n\tFOREIGN KEY(kb_id) REFERENCES knowledge_bases (id), \n\tFOREIGN KEY(job_id) REFERENCES jobs (id)\n)\n\n')
    op.execute('CREATE INDEX ix_index_builds_kb_id ON index_builds (kb_id)')
    op.execute('\nCREATE TABLE job_attempts (\n\tid INTEGER NOT NULL AUTO_INCREMENT, \n\tjob_id VARCHAR(32) NOT NULL, \n\ttoken INTEGER NOT NULL, \n\tstarted_at DATETIME NOT NULL, \n\tfinished_at DATETIME, \n\tstatus VARCHAR(24) NOT NULL, \n\tPRIMARY KEY (id), \n\tUNIQUE (job_id, token), \n\tFOREIGN KEY(job_id) REFERENCES jobs (id)\n)\n\n')
    op.execute('\nCREATE TABLE outbox_events (\n\tid VARCHAR(32) NOT NULL, \n\tjob_id VARCHAR(32) NOT NULL, \n\tsent_at DATETIME, \n\tlease_until DATETIME, \n\tclaim VARCHAR(32), \n\tcreated_at DATETIME NOT NULL, \n\tPRIMARY KEY (id), \n\tFOREIGN KEY(job_id) REFERENCES jobs (id)\n)\n\n')
    op.execute('CREATE INDEX ix_outbox_events_job_id ON outbox_events (job_id)')
    op.execute('\nCREATE TABLE run_events (\n\tid INTEGER NOT NULL AUTO_INCREMENT, \n\tjob_id VARCHAR(32) NOT NULL, \n\ttoken INTEGER NOT NULL, \n\tseq INTEGER NOT NULL, \n\tkind VARCHAR(30) NOT NULL, \n\tdata JSON NOT NULL, \n\tcreated_at DATETIME NOT NULL, \n\tPRIMARY KEY (id), \n\tUNIQUE (job_id, seq), \n\tFOREIGN KEY(job_id) REFERENCES jobs (id)\n)\n\n')
    op.execute('CREATE INDEX ix_run_events_job_id ON run_events (job_id)')
    op.execute('\nCREATE TABLE action_executions (\n\tid VARCHAR(32) NOT NULL, \n\tproposal_id VARCHAR(32) NOT NULL, \n\tstore_id INTEGER NOT NULL, \n\tidempotency_key VARCHAR(100) NOT NULL, \n\treceipt JSON NOT NULL, \n\tPRIMARY KEY (id), \n\tUNIQUE (store_id, idempotency_key), \n\tUNIQUE (proposal_id), \n\tFOREIGN KEY(proposal_id) REFERENCES action_proposals (id), \n\tFOREIGN KEY(store_id) REFERENCES store (id)\n)\n\n')
    op.execute('\nCREATE TABLE document_versions (\n\tid VARCHAR(32) NOT NULL, \n\tdocument_id VARCHAR(32) NOT NULL, \n\tobject_key VARCHAR(300) NOT NULL, \n\tdigest VARCHAR(64) NOT NULL, \n\tfilename VARCHAR(200) NOT NULL, \n\tcreated_at DATETIME NOT NULL, \n\tPRIMARY KEY (id), \n\tFOREIGN KEY(document_id) REFERENCES documents (id)\n)\n\n')
    op.execute('\nCREATE TABLE knowledge_chunks (\n\tid VARCHAR(32) NOT NULL, \n\tbuild_id VARCHAR(32) NOT NULL, \n\tdocument_id VARCHAR(32) NOT NULL, \n\tversion_id VARCHAR(32) NOT NULL, \n\tposition INTEGER NOT NULL, \n\tlocation VARCHAR(160) NOT NULL, \n\ttext TEXT NOT NULL, \n\tPRIMARY KEY (id), \n\tFOREIGN KEY(build_id) REFERENCES index_builds (id), \n\tFOREIGN KEY(document_id) REFERENCES documents (id), \n\tFOREIGN KEY(version_id) REFERENCES document_versions (id)\n)\n\n')
    op.execute('CREATE INDEX ix_knowledge_chunks_build_id ON knowledge_chunks (build_id)')
    op.execute('CREATE INDEX ix_knowledge_chunks_document_id ON knowledge_chunks (document_id)')


def downgrade():
    op.drop_table('knowledge_chunks')
    op.drop_table('document_versions')
    op.drop_table('action_executions')
    op.drop_table('run_events')
    op.drop_table('outbox_events')
    op.drop_table('job_attempts')
    op.drop_table('index_builds')
    op.drop_table('documents')
    op.drop_table('action_proposals')
    op.drop_table('store_memberships')
    op.drop_table('knowledge_bases')
    op.drop_table('jobs')
    op.drop_table('audit_logs')
    op.drop_index('ix_orders_store_status_time',table_name='orders')
    op.drop_constraint('uq_products_store_sku','products',type_='unique')
    # Restoring a global SKU constraint may fail when stores now share a SKU; resolve explicitly.
    op.create_unique_constraint('uq_products_sku','products',['sku'])
    op.drop_column('products','version')
    op.drop_column('store','timezone')
    op.drop_column('product_title_drafts','product_version')
