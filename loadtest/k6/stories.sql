\set ON_ERROR_STOP on
-- Supply the SAME MANYAK_OFFICIAL_USER_PUBLIC_ID as the server. No ORIGINAL enum exists.
SELECT COALESCE(json_agg(s.public_id::text ORDER BY s.id), '[]'::json)
FROM stories s JOIN users u ON u.id=s.user_id
WHERE u.public_id=:'official_user_public_id'::uuid
  AND s.status='PUBLISHED' AND s.visibility='PUBLIC' AND s.deleted_at IS NULL;
