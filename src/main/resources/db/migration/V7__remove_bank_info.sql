-- Remove bank information columns from auctions table
ALTER TABLE auctions
DROP COLUMN bank_name,
DROP COLUMN bank_account; 