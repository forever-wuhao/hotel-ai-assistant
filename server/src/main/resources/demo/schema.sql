CREATE TABLE IF NOT EXISTS hotel (
 id INT PRIMARY KEY, name VARCHAR(200), city VARCHAR(100), address VARCHAR(300),
 latitude DECIMAL(12,8), longitude DECIMAL(12,8), star_rating VARCHAR(20),
 min_price DECIMAL(10,2), facilities VARCHAR(500), description VARCHAR(2000), status INT
);
CREATE TABLE IF NOT EXISTS room_type (
 id INT PRIMARY KEY, hotel_id INT, name VARCHAR(100), price DECIMAL(10,2),
 stock INT, status INT, bed_count INT, bed_type VARCHAR(100), breakfast_count INT,
 cancel_policy VARCHAR(1000)
);
CREATE TABLE IF NOT EXISTS room_inventory_daily (
 room_type_id INT NOT NULL, stay_date DATE NOT NULL, available_rooms INT NOT NULL,
 nightly_price DECIMAL(10,2) NOT NULL, updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY (room_type_id, stay_date), CHECK (available_rooms >= 0), CHECK (nightly_price >= 0)
);
