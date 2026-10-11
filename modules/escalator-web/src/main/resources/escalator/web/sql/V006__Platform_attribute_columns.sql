-- Platform columns become attribute_type (attributes table + FK + family CHECK), like betbot V186.
-- Closed families are seeded here; OPEN families also get the register_open_domain_attribute trigger so new values
-- (e.g. a new crosswalk system or feedback outcome) self-register on first write.

-- Open families only accept slug-like idents, so a value can always become a generated Scala name.
CREATE OR REPLACE FUNCTION register_open_domain_attribute() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE value_ attribute_type;
BEGIN
 value_ := jsonb_populate_record(NULL::attribute_type, to_jsonb(NEW)->TG_ARGV[0]);
 IF value_ IS NOT NULL THEN
   IF value_.attr <> TG_ARGV[1] THEN RAISE EXCEPTION 'Wrong attribute family for %.%', TG_TABLE_NAME, TG_ARGV[0]; END IF;
   IF value_.ident !~ '^[A-Za-z][A-Za-z0-9_-]{0,63}$' THEN
     RAISE EXCEPTION 'Invalid % value "%": use letters, digits, _ or -', TG_ARGV[1], value_.ident USING ERRCODE = '22023';
   END IF;
   INSERT INTO attributes(attr_type, description) VALUES (value_, value_.ident) ON CONFLICT (attr_type) DO NOTHING;
 END IF;
 RETURN NEW;
END $$;

CREATE FUNCTION to_attribute_column(table_ text, column_ text, family_ text, values_ text[], open_ boolean, upper_ boolean DEFAULT false)
RETURNS void LANGUAGE plpgsql AS $$
DECLARE default_ text; v text;
BEGIN
  PERFORM seed_attributes(family_, values_);
  -- register values already present
  EXECUTE format('INSERT INTO attributes(attr_type, description) SELECT DISTINCT ROW(%L, %s)::attribute_type, %I::text FROM %I WHERE %I IS NOT NULL ON CONFLICT (attr_type) DO NOTHING',
    family_, CASE WHEN upper_ THEN format('upper(%I)', column_) ELSE format('%I', column_) END, column_, table_, column_);
  SELECT pg_get_expr(d.adbin, d.adrelid) INTO default_ FROM pg_attrdef d
    JOIN pg_attribute a ON a.attrelid = d.adrelid AND a.attnum = d.adnum
    WHERE d.adrelid = table_::regclass AND a.attname = column_;
  EXECUTE format('ALTER TABLE %I ALTER COLUMN %I DROP DEFAULT', table_, column_);
  EXECUTE format('ALTER TABLE %I ALTER COLUMN %I TYPE attribute_type USING CASE WHEN %I IS NULL THEN NULL ELSE ROW(%L, %s)::attribute_type END',
    table_, column_, column_, family_, CASE WHEN upper_ THEN format('upper(%I)', column_) ELSE format('%I', column_) END);
  IF default_ IS NOT NULL THEN
    EXECUTE format('ALTER TABLE %I ALTER COLUMN %I SET DEFAULT ROW(%L, (%s)::text)::attribute_type', table_, column_, family_, default_);
  END IF;
  EXECUTE format('ALTER TABLE %I ADD CONSTRAINT %I FOREIGN KEY (%I) REFERENCES attributes(attr_type) ON UPDATE CASCADE', table_, table_ || '_' || column_ || '_attr_fk', column_);
  EXECUTE format('ALTER TABLE %I ADD CONSTRAINT %I CHECK ((%I).attr = %L)', table_, table_ || '_' || column_ || '_attr_family', column_, family_);
  IF open_ THEN
    EXECUTE format('CREATE TRIGGER %I BEFORE INSERT OR UPDATE OF %I ON %I FOR EACH ROW EXECUTE FUNCTION register_open_domain_attribute(%L, %L)',
      'register_' || table_ || '_' || column_, column_, table_, column_, family_);
  END IF;
END $$;

-- platform tables (V002–V006)
-- platform tables (V002–V005). Apps add their own values: INTERACTION_SOURCE / INTERACTION_REASON are open
-- families, so a new model or reason self-registers on first write.
SELECT to_attribute_column('user_sessions', 'role', 'USER_ROLE', ARRAY['ADMIN','USER'], false);
SELECT to_attribute_column('interactions', 'source_model', 'INTERACTION_SOURCE', ARRAY['User'], true);
SELECT to_attribute_column('interactions', 'reason', 'INTERACTION_REASON', ARRAY['email_code'], true);
SELECT to_attribute_column('workspace_files', 'purpose', 'FILE_PURPOSE', ARRAY['upload'], false);
