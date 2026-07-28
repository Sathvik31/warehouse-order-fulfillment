-- Seed a few items for the demo
INSERT INTO items (sku, name, description, category, unit_of_measure) VALUES
                                                                          ('WIDGET-001', 'Blue Steel Widget', 'Standard industrial widget, blue variant', 'HARDWARE', 'EACH'),
                                                                          ('WIDGET-002', 'Red Steel Widget', 'Standard industrial widget, red variant', 'HARDWARE', 'EACH'),
                                                                          ('BOLT-M8-25', 'M8 x 25mm Hex Bolt', 'Stainless steel hex bolt', 'FASTENERS', 'EACH'),
                                                                          ('BOLT-M8-50', 'M8 x 50mm Hex Bolt', 'Stainless steel hex bolt', 'FASTENERS', 'EACH'),
                                                                          ('CABLE-USB-C', 'USB-C Cable 1m', 'USB-C to USB-C, 1 meter', 'ELECTRICAL', 'EACH');

-- Seed stock levels for each item in the default warehouse
INSERT INTO stock (item_id, warehouse_id, quantity_on_hand, publish_threshold)
SELECT id, 'W-DEFAULT', 100, 20 FROM items WHERE sku = 'WIDGET-001';

INSERT INTO stock (item_id, warehouse_id, quantity_on_hand, publish_threshold)
SELECT id, 'W-DEFAULT', 50, 10 FROM items WHERE sku = 'WIDGET-002';

INSERT INTO stock (item_id, warehouse_id, quantity_on_hand, publish_threshold)
SELECT id, 'W-DEFAULT', 500, 100 FROM items WHERE sku = 'BOLT-M8-25';

INSERT INTO stock (item_id, warehouse_id, quantity_on_hand, publish_threshold)
SELECT id, 'W-DEFAULT', 300, 50 FROM items WHERE sku = 'BOLT-M8-50';

INSERT INTO stock (item_id, warehouse_id, quantity_on_hand, publish_threshold)
SELECT id, 'W-DEFAULT', 200, 30 FROM items WHERE sku = 'CABLE-USB-C';