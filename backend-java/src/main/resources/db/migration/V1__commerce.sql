CREATE TABLE users (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 username VARCHAR(50) NOT NULL UNIQUE,
 password_hash VARCHAR(255) NOT NULL
) ENGINE=InnoDB;
CREATE TABLE stores (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 owner_id BIGINT NOT NULL,
 name VARCHAR(100) NOT NULL,
 platform VARCHAR(30) NOT NULL,
 data_version BIGINT NOT NULL DEFAULT 0,
 FOREIGN KEY(owner_id) REFERENCES users(id)
) ENGINE=InnoDB;
CREATE TABLE store_memberships (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 store_id BIGINT NOT NULL,
 user_id BIGINT NOT NULL,
 role VARCHAR(20) NOT NULL,
 active BOOLEAN NOT NULL DEFAULT TRUE,
 UNIQUE(store_id,user_id),
 CHECK(role IN ('viewer','operator')),
 FOREIGN KEY(store_id) REFERENCES stores(id),
 FOREIGN KEY(user_id) REFERENCES users(id)
) ENGINE=InnoDB;
CREATE TABLE products (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 store_id BIGINT NOT NULL,
 sku VARCHAR(30) NOT NULL,
 title VARCHAR(200) NOT NULL,
 price DECIMAL(18,2) NOT NULL,
 currency CHAR(3) NOT NULL,
 stock INT NOT NULL DEFAULT 0,
 version BIGINT NOT NULL DEFAULT 1,
 UNIQUE(store_id,sku), UNIQUE(store_id,id),
 CHECK(price>=0), CHECK(stock>=0),
 FOREIGN KEY(store_id) REFERENCES stores(id)
) ENGINE=InnoDB;
CREATE TABLE orders (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 store_id BIGINT NOT NULL,
 external_order_id VARCHAR(100) NOT NULL,
 ordered_at DATETIME(6) NOT NULL,
 status VARCHAR(20) NOT NULL,
 currency CHAR(3) NOT NULL,
 UNIQUE(store_id,external_order_id), UNIQUE(store_id,id),
 INDEX ix_orders_store_time(store_id,ordered_at),
 CHECK(status IN ('pending','paid','shipped','cancelled')),
 FOREIGN KEY(store_id) REFERENCES stores(id)
) ENGINE=InnoDB;
CREATE TABLE order_items (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 store_id BIGINT NOT NULL,
 order_id BIGINT NOT NULL,
 product_id BIGINT NOT NULL,
 line_no INT NOT NULL,
 quantity INT NOT NULL,
 unit_price DECIMAL(18,2) NOT NULL,
 refund_amount DECIMAL(18,2) NOT NULL DEFAULT 0,
 UNIQUE(order_id,line_no),
 FOREIGN KEY(store_id,order_id) REFERENCES orders(store_id,id),
 FOREIGN KEY(store_id,product_id) REFERENCES products(store_id,id),
 CHECK(line_no>0), CHECK(quantity>0), CHECK(unit_price>=0),
 CHECK(refund_amount>=0 AND refund_amount<=quantity*unit_price)
) ENGINE=InnoDB;
CREATE TABLE import_batches (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 store_id BIGINT NOT NULL,
 kind VARCHAR(20) NOT NULL,
 file_hash CHAR(64) NOT NULL,
 row_count INT NOT NULL,
 data_version BIGINT NOT NULL,
 created_by BIGINT NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE(store_id,kind,file_hash),
 FOREIGN KEY(store_id) REFERENCES stores(id),
 FOREIGN KEY(created_by) REFERENCES users(id)
) ENGINE=InnoDB;
