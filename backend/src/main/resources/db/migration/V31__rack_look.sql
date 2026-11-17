-- v2.1: how a rack type looks in 3D. PALLET racking (open beams, pallets with cartons) or SHELF shelving (solid
-- shelves, cartons straight on them), and the colours of its uprights and beams. Existing types keep the classic look.
ALTER TABLE rack_template
  ADD COLUMN style ENUM('PALLET','SHELF') NOT NULL DEFAULT 'PALLET',
  ADD COLUMN upright_color CHAR(7) NOT NULL DEFAULT '#1d4ed8',
  ADD COLUMN beam_color CHAR(7) NOT NULL DEFAULT '#f97316';
