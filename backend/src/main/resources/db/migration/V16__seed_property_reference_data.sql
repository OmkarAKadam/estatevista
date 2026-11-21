-- Stage 4.4 controlled reference-data load.
-- Source: docs/DATABASE_DESIGN.md §6.1/§6.2/§8.1 and docs/STAGE_4_DECISION_REGISTER.md D-09/G-08.
--
-- Categories are already seeded by V4 and are not modified. Subtype and amenity
-- names below are the approved initial values adopted by D-09; no new name or
-- database field is introduced. IDs are deterministic identifiers allocated by
-- this migration, not values claimed from the design documents. Re-running the
-- statements against a database that already contains a matching ID or unique
-- name is a no-op, so existing references are preserved.

INSERT INTO property_subtypes (id, category_id, name)
SELECT v.id, v.category_id, v.name
FROM (VALUES
    ('00000000-0000-0000-0000-000000002001'::uuid,
     '00000000-0000-0000-0000-000000000101'::uuid, 'Apartment'),
    ('00000000-0000-0000-0000-000000002002'::uuid,
     '00000000-0000-0000-0000-000000000101'::uuid, 'Villa'),
    ('00000000-0000-0000-0000-000000002003'::uuid,
     '00000000-0000-0000-0000-000000000101'::uuid, 'Independent House'),
    ('00000000-0000-0000-0000-000000002004'::uuid,
     '00000000-0000-0000-0000-000000000101'::uuid, 'Penthouse'),
    ('00000000-0000-0000-0000-000000002005'::uuid,
     '00000000-0000-0000-0000-000000000101'::uuid, 'Residential Plot'),
    ('00000000-0000-0000-0000-000000002006'::uuid,
     '00000000-0000-0000-0000-000000000102'::uuid, 'Office Space'),
    ('00000000-0000-0000-0000-000000002007'::uuid,
     '00000000-0000-0000-0000-000000000102'::uuid, 'Retail Shop'),
    ('00000000-0000-0000-0000-000000002008'::uuid,
     '00000000-0000-0000-0000-000000000102'::uuid, 'Showroom'),
    ('00000000-0000-0000-0000-000000002009'::uuid,
     '00000000-0000-0000-0000-000000000102'::uuid, 'Warehouse'),
    ('00000000-0000-0000-0000-000000002010'::uuid,
     '00000000-0000-0000-0000-000000000102'::uuid, 'Commercial Plot')
) AS v(id, category_id, name)
WHERE NOT EXISTS (
    SELECT 1
    FROM property_subtypes ps
    WHERE ps.id = v.id
       OR (ps.category_id = v.category_id AND ps.name = v.name)
);

INSERT INTO amenities (id, name)
SELECT v.id, v.name
FROM (VALUES
    ('00000000-0000-0000-0000-000000003001'::uuid, 'Swimming Pool'),
    ('00000000-0000-0000-0000-000000003002'::uuid, 'Gym'),
    ('00000000-0000-0000-0000-000000003003'::uuid, 'Parking'),
    ('00000000-0000-0000-0000-000000003004'::uuid, 'Security'),
    ('00000000-0000-0000-0000-000000003005'::uuid, 'Garden'),
    ('00000000-0000-0000-0000-000000003006'::uuid, 'Lift'),
    ('00000000-0000-0000-0000-000000003007'::uuid, 'Power Backup'),
    ('00000000-0000-0000-0000-000000003008'::uuid, 'Clubhouse')
) AS v(id, name)
WHERE NOT EXISTS (
    SELECT 1
    FROM amenities a
    WHERE a.id = v.id
       OR a.name = v.name
);
