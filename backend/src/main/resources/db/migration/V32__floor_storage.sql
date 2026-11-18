-- v2.1: floor storage (block stacking). A FLOOR rack type is a row of pallet positions on the floor: no uprights or
-- beams; its levels are how many pallets high goods may stack. Everything else (places, codes, labels) is as for racks.
ALTER TABLE rack_template MODIFY COLUMN style ENUM('PALLET','SHELF','FLOOR') NOT NULL DEFAULT 'PALLET';
