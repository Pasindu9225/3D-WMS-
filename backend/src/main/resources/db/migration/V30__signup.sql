-- v2.0 task 2: self-service sign-up. Nothing is created until the email is confirmed; until then the request waits
-- here with the chosen password already hashed and only a hash of the one-time link token (SEC-03 style).
CREATE TABLE signup_request (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  email VARCHAR(190) NOT NULL,
  company_name VARCHAR(150) NOT NULL,
  full_name VARCHAR(120) NOT NULL,
  password_hash VARCHAR(255) NOT NULL,
  token_hash CHAR(64) NOT NULL,
  ip VARCHAR(45) NULL,
  created_at DATETIME(3) NOT NULL,
  expires_at DATETIME(3) NOT NULL,
  used_at DATETIME(3) NULL,
  UNIQUE KEY uk_signup_token (token_hash),
  KEY ix_signup_email (email),
  KEY ix_signup_created (created_at)
);
