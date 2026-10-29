CREATE TABLE claim_receipt (
 id uuid PRIMARY KEY,
 claim_id uuid REFERENCES claim(id) ON DELETE SET NULL,
 owner_id bigint NOT NULL,
 object_key text NOT NULL UNIQUE,
 filename varchar(200) NOT NULL,
 content_type varchar(100) NOT NULL,
 size bigint NOT NULL CHECK(size > 0 AND size <= 10485760),
 expires_at timestamptz NOT NULL,
 finalized boolean NOT NULL DEFAULT false,
 deleted boolean NOT NULL DEFAULT false,
 created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX claim_receipt_claim ON claim_receipt(claim_id);
CREATE INDEX claim_receipt_expiry ON claim_receipt(expires_at) WHERE finalized=false OR deleted=true OR claim_id IS NULL;
