-- Escalator attribute pattern: typed enum values (attr family, ident) shared by all tables.
CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE TYPE attribute_type AS (
   attr  character varying(255),
   ident character varying(255)
);

CREATE FUNCTION attribute_type_to_string(attribute_type) RETURNS varchar AS $$
BEGIN
    RETURN ($1.attr || ' ' || $1.ident);
END;
$$ LANGUAGE plpgsql IMMUTABLE;

CREATE TABLE attributes (
    id          BIGSERIAL PRIMARY KEY,
    attr_type   attribute_type NOT NULL,
    ordering    integer,
    description character varying(255) NOT NULL,
    created_at  timestamp without time zone NOT NULL DEFAULT NOW(),
    updated_at  timestamp without time zone NOT NULL DEFAULT NOW()
);
CREATE UNIQUE INDEX attributes_uidx ON attributes(attr_type);

-- Open families: unknown values are registered on first write (trigger arg: column, family).
CREATE FUNCTION register_open_domain_attribute() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE value_ attribute_type;
BEGIN
 value_ := jsonb_populate_record(NULL::attribute_type, to_jsonb(NEW)->TG_ARGV[0]);
 IF value_ IS NOT NULL THEN
   IF value_.attr <> TG_ARGV[1] THEN RAISE EXCEPTION 'Wrong attribute family for %.%', TG_TABLE_NAME, TG_ARGV[0]; END IF;
   INSERT INTO attributes(attr_type, description) VALUES (value_, value_.ident) ON CONFLICT (attr_type) DO NOTHING;
 END IF;
 RETURN NEW;
END $$;

-- Small helper so later migrations can seed a family in one line.
CREATE FUNCTION seed_attributes(family text, idents text[]) RETURNS void LANGUAGE plpgsql AS $$
DECLARE i int := 0; v text;
BEGIN
  FOREACH v IN ARRAY idents LOOP
    i := i + 1;
    INSERT INTO attributes(attr_type, ordering, description) VALUES (ROW(family, v)::attribute_type, i, v)
    ON CONFLICT (attr_type) DO NOTHING;
  END LOOP;
END $$;
