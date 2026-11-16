-- Apply deliberately to your MySQL database. Never auto-run in the live profile.
CREATE TABLE IF NOT EXISTS room_inventory_daily (
  room_type_id INT NOT NULL,
  stay_date DATE NOT NULL,
  available_rooms INT NOT NULL,
  nightly_price DECIMAL(10,2) NOT NULL,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (room_type_id, stay_date),
  CONSTRAINT inventory_rooms_nonnegative CHECK (available_rooms >= 0),
  CONSTRAINT inventory_price_nonnegative CHECK (nightly_price >= 0)
);
